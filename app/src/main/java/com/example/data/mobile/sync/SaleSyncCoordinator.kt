package com.example.data.mobile.sync

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.Rfc3339Validator
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.outbox.OutboxStatus
import com.example.data.mobile.outbox.QueueLockedException
import java.util.concurrent.CancellationException

/**
 * Result metrics for a bounded synchronization pass.
 */
data class SyncBatchResult(
    val recordsClaimed: Int = 0,
    val confirmedCount: Int = 0,
    val conflictCount: Int = 0,
    val parkedV1Count: Int = 0,
    val timeoutCount: Int = 0,
    val authBlocked: Boolean = false,
    val membershipDenied: Boolean = false,
    val stoppedEarly: Boolean = false,
    val summary: String = ""
)

/**
 * Fixed local diagnostic constants for outbox state, auth locks, and metrics reporting.
 * Discards all untrusted gateway reasons and exception messages to prevent secret or PII leakage.
 */
object SyncDiagnostics {
    const val BUSINESS_CONFLICT = "BUSINESS_CONFLICT"
    const val AUTH_DENIED = "AUTH_DENIED"
    const val MEMBERSHIP_DENIED = "MEMBERSHIP_DENIED"
    const val TRANSIENT_TIMEOUT = "TRANSIENT_TIMEOUT"
    const val TRANSIENT_ERROR = "TRANSIENT_ERROR"
    const val CANCELLED_IN_FLIGHT = "CANCELLED_IN_FLIGHT"
    const val MALFORMED_CLIENT_EVENT_ID = "MALFORMED_STORED_CLIENT_EVENT_ID"
    const val MALFORMED_PAYLOAD_DIGEST = "MALFORMED_STORED_PAYLOAD_DIGEST"
    const val CORRUPT_PAYLOAD_DIGEST = "CORRUPT_PAYLOAD_DIGEST_MISMATCH"
    const val PAYLOAD_OVERSIZED = "PAYLOAD_OVERSIZED"
    const val LEGACY_EVENT_VERSION = "LEGACY_EVENT_VERSION_PARKED"
    const val INVALID_SERVER_ACK = "INVALID_SERVER_ACK"
    const val QUEUE_LOCKED_AUTH_DENIED = "QUEUE_LOCKED_AUTH_DENIED"
    const val CLAIM_BATCH_FAILED = "CLAIM_BATCH_FAILED"
    const val STALE_CLAIM_SUPPRESSED = "STALE_CLAIM_SUPPRESSED"
}

/**
 * Local sync coordinator foundation interface.
 *
 * Coordinates local outbox drain against a typed [SaleSyncGateway] using lease-bounded CAS.
 *
 * CORE INVARIANTS:
 * 1. Bounded batch: 1..100 records claimed via repository CAS lease.
 * 2. Full tenant/user/device scope isolation (no cross-scope payload or digest disclosure).
 * 3. Concurrent coordinators cannot drain the same claimed record (CAS claim lease + claim token).
 * 4. Re-reads scoped record and checks persisted scope lock + valid unexpired lease before EVERY network dispatch.
 * 5. Recomputes raw payload SHA-256 digest before gateway status or submit; corrupt bytes are parked with zero submission.
 * 6. For EACH claimed v2 record, reconciles gateway status BEFORE any submit (including UNKNOWN_ACK).
 * 7. For status Posted: validates exact company, event, digest, invoice ULID, payment ULID, and strict RFC 3339 serverAcknowledgedAt.
 *    Malformed or mismatched acks are NEVER confirmed and local payloads are NEVER replaced.
 * 8. For status NotFound: submits ONLY the original canonical bare event bytes without local repricing or rekeying.
 * 9. Legacy v1 records (eventVersion != 2) are parked without post or silent upgrade.
 * 10. Malformed stored UUIDs are parked via narrow raw-string key scope+token guarded repository method.
 * 11. 401 AuthDenied locks the queue across the scope via lockQueueOnAuthDenied, preserving records and payloads.
 * 12. 403 MembershipDenied fails closed without retry loops.
 * 13. 409 BusinessConflict marks ONLY the owned non-confirmed record via claim-bound markClaimConflict.
 * 14. Transient/drop/5xx/unknownACK moves claim through recordTimeout and stops without repeated in-turn retries.
 * 15. Early batch stop safely clears ownership of remaining owned in-flight claims via narrow CAS scope+token.
 * 16. Fatal errors (OutOfMemoryError, LinkageError, AssertionError, ThreadDeath) and cancellation propagate without heavy DB cleanup.
 * 17. Discards ALL untrusted gateway strings, exception messages, and hostile class names; uses fixed diagnostics only.
 */
interface SaleSyncCoordinator {

    fun synchronizeScope(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        batchSize: Int = 10,
        leaseDurationMillis: Long = 30_000L
    ): SyncBatchResult
}

class SaleSyncCoordinatorImpl(
    private val outboxRepository: OutboxRepository,
    private val gateway: SaleSyncGateway,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SaleSyncCoordinator {

    override fun synchronizeScope(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        batchSize: Int,
        leaseDurationMillis: Long
    ): SyncBatchResult {
        require(batchSize in 1..OutboxRepository.MAX_BATCH_SIZE) {
            "batchSize must be between 1 and ${OutboxRepository.MAX_BATCH_SIZE} (was $batchSize)"
        }
        require(leaseDurationMillis in 1_000L..OutboxRepository.MAX_LEASE_DURATION_MILLIS) {
            "leaseDurationMillis must be between 1,000 and ${OutboxRepository.MAX_LEASE_DURATION_MILLIS} ms (was $leaseDurationMillis)"
        }

        // 1. Guard against executing if scope is already locked due to auth denial
        if (outboxRepository.isQueueLocked(companyId, actorId, deviceId)) {
            return SyncBatchResult(
                recordsClaimed = 0,
                authBlocked = true,
                stoppedEarly = true,
                summary = SyncDiagnostics.QUEUE_LOCKED_AUTH_DENIED
            )
        }

        // 2. Claim next eligible batch using lease-bounded CAS. Propagate fatal errors and cancellation.
        val batch = try {
            outboxRepository.claimNextBatch(
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                batchSize = batchSize,
                leaseDurationMillis = leaseDurationMillis
            )
        } catch (e: QueueLockedException) {
            return SyncBatchResult(
                recordsClaimed = 0,
                authBlocked = true,
                stoppedEarly = true,
                summary = SyncDiagnostics.QUEUE_LOCKED_AUTH_DENIED
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            return SyncBatchResult(
                recordsClaimed = 0,
                stoppedEarly = true,
                summary = SyncDiagnostics.CLAIM_BATCH_FAILED
            )
        }

        if (batch.records.isEmpty()) {
            return SyncBatchResult(
                recordsClaimed = 0,
                summary = "Queue empty; no eligible records claimed"
            )
        }

        var confirmedCount = 0
        var conflictCount = 0
        var parkedV1Count = 0
        var timeoutCount = 0
        var authBlocked = false
        var membershipDenied = false
        var stoppedEarly = false

        // 3. Process each claimed record individually
        for (record in batch.records) {
            val event = record.event

            // Invariant: Malformed stored UUID must not remain poison reclaimed forever.
            // Park using narrow raw string key scope/token guarded method.
            val clientEventId = try {
                ClientUuid.parse(event.clientEventId)
            } catch (e: Exception) {
                val updated = outboxRepository.parkClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    rawClientEventId = event.clientEventId,
                    claimToken = batch.claimToken,
                    reason = SyncDiagnostics.MALFORMED_CLIENT_EVENT_ID
                )
                if (updated) conflictCount++
                continue
            }

            // Invariant: Validate stored payload digest format
            val payloadDigest = try {
                PayloadDigest.parse(event.payloadDigest)
            } catch (e: Exception) {
                val updated = outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = SyncDiagnostics.MALFORMED_PAYLOAD_DIGEST
                )
                if (updated) conflictCount++
                continue
            }

            // Invariant: Recompute RAW payload SHA-256 hash before gateway status/submit.
            // Check size bound and reject/park corrupt bytes with generic reason; zero submission.
            // Immutable original bytes retained in Room (no repair/reprice).
            if (event.payloadBytes.size > ContractConstants.MAX_PAYLOAD_BYTES) {
                val updated = outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = SyncDiagnostics.PAYLOAD_OVERSIZED
                )
                if (updated) conflictCount++
                continue
            }

            val computedDigest = PayloadDigest.fromBytes(event.payloadBytes)
            if (computedDigest != payloadDigest) {
                val updated = outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = SyncDiagnostics.CORRUPT_PAYLOAD_DIGEST
                )
                if (updated) conflictCount++
                continue
            }

            // Invariant: Missing or legacy v1 records preserved; parked without post or silent upgrade
            if (event.eventVersion != 2) {
                val updated = outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = SyncDiagnostics.LEGACY_EVENT_VERSION
                )
                if (updated) {
                    parkedV1Count++
                    conflictCount++
                }
                continue
            }

            // Invariant: Before EVERY network dispatch (status and submit), re-read scoped record:
            // Verify status IN_FLIGHT, same claimToken, unexpired lease, and persisted scope lock.
            // Suppress known stale/post-auth-lock dispatch; no double claim overwrite.
            // Note: Race between guard and wire still requires backend idempotency; CAS cannot guarantee
            // exactly-once HTTP/MariaDB delivery.
            if (!isClaimValidAndUnexpired(companyId, actorId, deviceId, clientEventId, batch.claimToken)) {
                stoppedEarly = true
                break
            }

            // Invariant: Reconcile gateway status BEFORE any submit (including UNKNOWN_ACK)
            val statusOutcome = try {
                gateway.checkStatus(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    payloadDigest = payloadDigest
                )
            } catch (e: CancellationException) {
                try {
                    outboxRepository.recordTimeout(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        errorMessage = SyncDiagnostics.CANCELLED_IN_FLIGHT
                    )
                } catch (_: Exception) {}
                try {
                    outboxRepository.releaseClaimedBatch(companyId, actorId, deviceId, batch.claimToken)
                } catch (_: Exception) {}
                throw e
            } catch (e: InterruptedException) {
                try {
                    outboxRepository.recordTimeout(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        errorMessage = SyncDiagnostics.CANCELLED_IN_FLIGHT
                    )
                } catch (_: Exception) {}
                try {
                    outboxRepository.releaseClaimedBatch(companyId, actorId, deviceId, batch.claimToken)
                } catch (_: Exception) {}
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val updated = outboxRepository.recordTimeout(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    errorMessage = SyncDiagnostics.TRANSIENT_ERROR
                )
                if (updated) timeoutCount++
                stoppedEarly = true
                break
            }

            when (statusOutcome) {
                is SyncStatusOutcome.Posted -> {
                    // Event was already posted on server: validate exact acknowledgement fields
                    val ack = statusOutcome.ack
                    if (isValidAck(ack, companyId, clientEventId, payloadDigest)) {
                        val confirmed = try {
                            outboxRepository.confirmDelivery(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                payloadDigest = payloadDigest,
                                invoiceUlid = ack.invoiceUlid,
                                paymentUlid = ack.paymentUlid,
                                serverAcknowledgedAt = ack.serverAcknowledgedAt,
                                claimToken = batch.claimToken
                            )
                        } catch (e: Exception) {
                            false
                        }
                        if (confirmed) {
                            confirmedCount++
                        }
                    } else {
                        // Malformed or invalid ack (e.g. invalid date/format): fail without confirmation.
                        // Transition to UNKNOWN_ACK so it can recover via fresh status on next pass (not false conflict).
                        val updated = outboxRepository.recordTimeout(
                            companyId = companyId,
                            actorId = actorId,
                            deviceId = deviceId,
                            clientEventId = clientEventId,
                            claimToken = batch.claimToken,
                            errorMessage = SyncDiagnostics.INVALID_SERVER_ACK
                        )
                        if (updated) timeoutCount++
                    }
                }

                is SyncStatusOutcome.NotFound -> {
                    // Server does not have this record: submit original canonical bare event bytes.
                    // Guard: re-read scoped record immediately BEFORE submit to prevent stale dispatch.
                    if (!isClaimValidAndUnexpired(companyId, actorId, deviceId, clientEventId, batch.claimToken)) {
                        stoppedEarly = true
                        break
                    }

                    val canonicalBytesCopy = event.payloadBytes.clone()
                    val submitOutcome = try {
                        gateway.submitSale(
                            companyId = companyId,
                            actorId = actorId,
                            deviceId = deviceId,
                            clientEventId = clientEventId,
                            payloadDigest = payloadDigest,
                            canonicalEventUtf8 = canonicalBytesCopy
                        )
                    } catch (e: CancellationException) {
                        try {
                            outboxRepository.recordTimeout(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                errorMessage = SyncDiagnostics.CANCELLED_IN_FLIGHT
                            )
                        } catch (_: Exception) {}
                        try {
                            outboxRepository.releaseClaimedBatch(companyId, actorId, deviceId, batch.claimToken)
                        } catch (_: Exception) {}
                        throw e
                    } catch (e: InterruptedException) {
                        try {
                            outboxRepository.recordTimeout(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                errorMessage = SyncDiagnostics.CANCELLED_IN_FLIGHT
                            )
                        } catch (_: Exception) {}
                        try {
                            outboxRepository.releaseClaimedBatch(companyId, actorId, deviceId, batch.claimToken)
                        } catch (_: Exception) {}
                        Thread.currentThread().interrupt()
                        throw e
                    } catch (e: Exception) {
                        val updated = outboxRepository.recordTimeout(
                            companyId = companyId,
                            actorId = actorId,
                            deviceId = deviceId,
                            clientEventId = clientEventId,
                            claimToken = batch.claimToken,
                            errorMessage = SyncDiagnostics.TRANSIENT_ERROR
                        )
                        if (updated) timeoutCount++
                        stoppedEarly = true
                        break
                    }

                    when (submitOutcome) {
                        is SyncSubmitOutcome.Posted -> {
                            val ack = submitOutcome.ack
                            if (isValidAck(ack, companyId, clientEventId, payloadDigest)) {
                                val confirmed = try {
                                    outboxRepository.confirmDelivery(
                                        companyId = companyId,
                                        actorId = actorId,
                                        deviceId = deviceId,
                                        clientEventId = clientEventId,
                                        payloadDigest = payloadDigest,
                                        invoiceUlid = ack.invoiceUlid,
                                        paymentUlid = ack.paymentUlid,
                                        serverAcknowledgedAt = ack.serverAcknowledgedAt,
                                        claimToken = batch.claimToken
                                    )
                                } catch (e: Exception) {
                                    false
                                }
                                if (confirmed) {
                                    confirmedCount++
                                }
                            } else {
                                val updated = outboxRepository.recordTimeout(
                                    companyId = companyId,
                                    actorId = actorId,
                                    deviceId = deviceId,
                                    clientEventId = clientEventId,
                                    claimToken = batch.claimToken,
                                    errorMessage = SyncDiagnostics.INVALID_SERVER_ACK
                                )
                                if (updated) timeoutCount++
                            }
                        }

                        is SyncSubmitOutcome.BusinessConflict -> {
                            // 409 Business conflict: marks ONLY owned claimed non-confirmed record
                            val updated = outboxRepository.markClaimConflict(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                reason = SyncDiagnostics.BUSINESS_CONFLICT
                            )
                            if (updated) conflictCount++
                        }

                        is SyncSubmitOutcome.AuthDenied -> {
                            // 401 Auth denied: lock entire scope queue, transitioning all active records to auth_blocked
                            outboxRepository.lockQueueOnAuthDenied(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                reason = SyncDiagnostics.AUTH_DENIED
                            )
                            authBlocked = true
                            stoppedEarly = true
                            break
                        }

                        is SyncSubmitOutcome.MembershipDenied -> {
                            // 403 Membership denial: fail closed, mark conflict, no retry loops
                            val updated = outboxRepository.markClaimConflict(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                reason = SyncDiagnostics.MEMBERSHIP_DENIED
                            )
                            if (updated) conflictCount++
                            membershipDenied = true
                            stoppedEarly = true
                            break
                        }

                        is SyncSubmitOutcome.TransientUnknown -> {
                            // Transient error: record timeout to move claim to UNKNOWN_ACK; stop pass without in-turn retry
                            val diag = if (submitOutcome.isTimeout) SyncDiagnostics.TRANSIENT_TIMEOUT else SyncDiagnostics.TRANSIENT_ERROR
                            val updated = outboxRepository.recordTimeout(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                errorMessage = diag
                            )
                            if (updated) timeoutCount++
                            stoppedEarly = true
                            break
                        }
                    }
                }

                is SyncStatusOutcome.BusinessConflict -> {
                    val updated = outboxRepository.markClaimConflict(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        reason = SyncDiagnostics.BUSINESS_CONFLICT
                    )
                    if (updated) conflictCount++
                }

                is SyncStatusOutcome.AuthDenied -> {
                    outboxRepository.lockQueueOnAuthDenied(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        reason = SyncDiagnostics.AUTH_DENIED
                    )
                    authBlocked = true
                    stoppedEarly = true
                    break
                }

                is SyncStatusOutcome.MembershipDenied -> {
                    val updated = outboxRepository.markClaimConflict(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        reason = SyncDiagnostics.MEMBERSHIP_DENIED
                    )
                    if (updated) conflictCount++
                    membershipDenied = true
                    stoppedEarly = true
                    break
                }

                is SyncStatusOutcome.TransientUnknown -> {
                    val diag = if (statusOutcome.isTimeout) SyncDiagnostics.TRANSIENT_TIMEOUT else SyncDiagnostics.TRANSIENT_ERROR
                    val updated = outboxRepository.recordTimeout(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        errorMessage = diag
                    )
                    if (updated) timeoutCount++
                    stoppedEarly = true
                    break
                }
            }
        }

        // 4. Early batch stop cleanup: safely clear ownership of remaining uncompleted records
        // claimed in this batch. If authBlocked is true, lockQueueOnAuthDenied already transitioned
        // all active scope records to auth_blocked. Otherwise, release remaining owned in-flight
        // claims back to pending via narrow CAS scope+token.
        if (stoppedEarly && !authBlocked) {
            try {
                outboxRepository.releaseClaimedBatch(companyId, actorId, deviceId, batch.claimToken)
            } catch (_: Exception) {}
        }

        val summary = buildString {
            append("Claimed: ${batch.records.size}, Confirmed: $confirmedCount, Conflict: $conflictCount")
            if (parkedV1Count > 0) append(", ParkedV1: $parkedV1Count")
            if (timeoutCount > 0) append(", Timeout: $timeoutCount")
            if (authBlocked) append(", AuthBlocked: true")
            if (membershipDenied) append(", MembershipDenied: true")
            if (stoppedEarly) append(", StoppedEarly: true")
        }

        return SyncBatchResult(
            recordsClaimed = batch.records.size,
            confirmedCount = confirmedCount,
            conflictCount = conflictCount,
            parkedV1Count = parkedV1Count,
            timeoutCount = timeoutCount,
            authBlocked = authBlocked,
            membershipDenied = membershipDenied,
            stoppedEarly = stoppedEarly,
            summary = summary
        )
    }

    /**
     * Re-reads the scoped outbox record and checks:
     * 1. Persisted scope lock is NOT active.
     * 2. Record exists and belongs to the scope.
     * 3. Delivery status is IN_FLIGHT.
     * 4. Claim token matches the current batch claim token.
     * 5. Claim lease is unexpired according to clock().
     */
    private fun isClaimValidAndUnexpired(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        claimToken: String
    ): Boolean {
        if (outboxRepository.isQueueLocked(companyId, actorId, deviceId)) {
            return false
        }
        val record = outboxRepository.getRecord(companyId, actorId, deviceId, clientEventId) ?: return false
        val state = record.deliveryState
        if (state.status != OutboxStatus.IN_FLIGHT.wireValue) return false
        if (state.claimToken != claimToken) return false
        val expiresAt = state.claimExpiresAt ?: return false
        if (expiresAt <= clock()) return false
        return true
    }

    private fun isValidAck(
        ack: ServerSaleAck,
        expectedCompanyId: ServerUlid,
        expectedClientEventId: ClientUuid,
        expectedDigest: PayloadDigest
    ): Boolean {
        if (ack.companyId != expectedCompanyId) return false
        if (ack.clientEventId != expectedClientEventId) return false
        if (ack.payloadDigest != expectedDigest) return false
        if (!ServerUlid.isValid(ack.invoiceUlid.value)) return false
        if (!ServerUlid.isValid(ack.paymentUlid.value)) return false
        if (!Rfc3339Validator.isValid(ack.serverAcknowledgedAt)) return false
        return true
    }
}
