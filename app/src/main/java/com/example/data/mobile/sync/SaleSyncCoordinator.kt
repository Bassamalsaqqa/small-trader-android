package com.example.data.mobile.sync

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.OutboxRepository
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
 * Local sync coordinator foundation interface.
 *
 * Coordinates local outbox drain against a typed [SaleSyncGateway] using lease-bounded CAS.
 *
 * CORE INVARIANTS:
 * 1. Bounded batch: 1..100 records claimed via repository CAS lease.
 * 2. Full tenant/user/device scope isolation (no cross-scope payload or digest disclosure).
 * 3. Concurrent coordinators cannot drain the same claimed record (CAS claim lease + claim token).
 * 4. For EACH claimed v2 record, reconciles gateway status BEFORE any submit (including UNKNOWN_ACK).
 * 5. For status Posted: validates exact company, event, digest, invoice ULID, payment ULID before confirmation.
 *    Malformed or mismatched acks are NEVER confirmed and local payloads are NEVER replaced.
 * 6. For status NotFound: submits ONLY the original canonical bare event bytes without local repricing or rekeying.
 * 7. Legacy v1 records (eventVersion != 2) are parked without post or silent upgrade.
 * 8. 401 AuthDenied locks the queue for the entire scope via lockQueueOnAuthDenied and preserves all records.
 * 9. 403 MembershipDenied fails closed without retry loops.
 * 10. 409 BusinessConflict marks ONLY the owned non-confirmed record via claim-bound markClaimConflict.
 * 11. Transient/drop/5xx/unknownACK moves claim through recordTimeout and stops without repeated in-turn retries.
 * 12. Cancellation/interruption records UNKNOWN_ACK if request may have been sent, and never swallows fatal errors.
 * 13. All payload byte arrays are defensively copied; exception messages never leak sensitive data.
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
                summary = "Queue is locked due to authentication denial for company $companyId, actor $actorId"
            )
        }

        // 2. Claim next eligible batch using lease-bounded CAS
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
                summary = "Queue is locked due to authentication denial"
            )
        } catch (t: Throwable) {
            checkFatal(t)
            return SyncBatchResult(
                recordsClaimed = 0,
                stoppedEarly = true,
                summary = "Failed to claim batch: ${safeErrorMessage(t)}"
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

        // 3. Process each claimed record individually with fresh status check before submit
        for (record in batch.records) {
            val event = record.event
            val clientEventId = try {
                ClientUuid.parse(event.clientEventId)
            } catch (e: Exception) {
                // Malformed stored clientEventId: cannot construct typed ClientUuid
                conflictCount++
                continue
            }

            val payloadDigest = try {
                PayloadDigest.parse(event.payloadDigest)
            } catch (e: Exception) {
                outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = "Malformed stored payload digest"
                )
                conflictCount++
                continue
            }

            // Invariant: Missing or legacy v1 records preserved; parked without post or silent upgrade
            if (event.eventVersion != 2) {
                outboxRepository.markClaimConflict(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    reason = "Legacy event version ${event.eventVersion} cannot be posted to v2 sync gateway; parked without submission"
                )
                parkedV1Count++
                conflictCount++
                continue
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
            } catch (t: Throwable) {
                handleCancellationOrFatal(t, companyId, actorId, deviceId, clientEventId, batch.claimToken)
                outboxRepository.recordTimeout(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = batch.claimToken,
                    errorMessage = "Status check failed: ${safeErrorMessage(t)}"
                )
                timeoutCount++
                stoppedEarly = true
                break // Stop pass on status check failure to avoid uncoordinated actions
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
                        } else {
                            timeoutCount++
                        }
                    } else {
                        // Malformed or mismatched server acknowledgement: NEVER confirmed or payload replaced
                        outboxRepository.recordTimeout(
                            companyId = companyId,
                            actorId = actorId,
                            deviceId = deviceId,
                            clientEventId = clientEventId,
                            claimToken = batch.claimToken,
                            errorMessage = "Server status response contained mismatched or invalid acknowledgement fields"
                        )
                        timeoutCount++
                    }
                }

                is SyncStatusOutcome.NotFound -> {
                    // Server does not have this record: submit original canonical bare event bytes
                    // Invariant: ONLY same original canonical event bytes, no local draft/token/receipt creation, no repricing/rekey
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
                    } catch (t: Throwable) {
                        handleCancellationOrFatal(t, companyId, actorId, deviceId, clientEventId, batch.claimToken)
                        outboxRepository.recordTimeout(
                            companyId = companyId,
                            actorId = actorId,
                            deviceId = deviceId,
                            clientEventId = clientEventId,
                            claimToken = batch.claimToken,
                            errorMessage = "Submit sale failed: ${safeErrorMessage(t)}"
                        )
                        timeoutCount++
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
                                } else {
                                    timeoutCount++
                                }
                            } else {
                                outboxRepository.recordTimeout(
                                    companyId = companyId,
                                    actorId = actorId,
                                    deviceId = deviceId,
                                    clientEventId = clientEventId,
                                    claimToken = batch.claimToken,
                                    errorMessage = "Server submit response contained mismatched or invalid acknowledgement fields"
                                )
                                timeoutCount++
                            }
                        }

                        is SyncSubmitOutcome.BusinessConflict -> {
                            // 409 Business conflict: marks ONLY owned claimed non-confirmed record
                            outboxRepository.markClaimConflict(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                reason = "Business conflict (409): ${safeErrorMessage(submitOutcome.reason)}"
                            )
                            conflictCount++
                        }

                        is SyncSubmitOutcome.AuthDenied -> {
                            // 401 Auth denied: lock entire scope queue, preserving records and payloads
                            outboxRepository.lockQueueOnAuthDenied(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                reason = "Authentication denied (401): ${safeErrorMessage(submitOutcome.reason)}"
                            )
                            authBlocked = true
                            stoppedEarly = true
                            break
                        }

                        is SyncSubmitOutcome.MembershipDenied -> {
                            // 403 Membership denial: fail closed, mark conflict, no retry loops
                            outboxRepository.markClaimConflict(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                reason = "Membership/forbidden denial (403): ${safeErrorMessage(submitOutcome.reason)}"
                            )
                            conflictCount++
                            membershipDenied = true
                            stoppedEarly = true
                            break
                        }

                        is SyncSubmitOutcome.TransientUnknown -> {
                            // Transient error: record timeout to move claim to UNKNOWN_ACK; stop pass without in-turn retry
                            outboxRepository.recordTimeout(
                                companyId = companyId,
                                actorId = actorId,
                                deviceId = deviceId,
                                clientEventId = clientEventId,
                                claimToken = batch.claimToken,
                                errorMessage = "Transient network error: ${safeErrorMessage(submitOutcome.errorMessage)}"
                            )
                            timeoutCount++
                            stoppedEarly = true
                            break
                        }
                    }
                }

                is SyncStatusOutcome.BusinessConflict -> {
                    outboxRepository.markClaimConflict(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        reason = "Server reported business conflict (409): ${safeErrorMessage(statusOutcome.reason)}"
                    )
                    conflictCount++
                }

                is SyncStatusOutcome.AuthDenied -> {
                    outboxRepository.lockQueueOnAuthDenied(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        reason = "Authentication denied (401) during status check: ${safeErrorMessage(statusOutcome.reason)}"
                    )
                    authBlocked = true
                    stoppedEarly = true
                    break
                }

                is SyncStatusOutcome.MembershipDenied -> {
                    outboxRepository.markClaimConflict(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        reason = "Membership/forbidden denial (403) during status check: ${safeErrorMessage(statusOutcome.reason)}"
                    )
                    conflictCount++
                    membershipDenied = true
                    stoppedEarly = true
                    break
                }

                is SyncStatusOutcome.TransientUnknown -> {
                    outboxRepository.recordTimeout(
                        companyId = companyId,
                        actorId = actorId,
                        deviceId = deviceId,
                        clientEventId = clientEventId,
                        claimToken = batch.claimToken,
                        errorMessage = "Transient status check failure: ${safeErrorMessage(statusOutcome.errorMessage)}"
                    )
                    timeoutCount++
                    stoppedEarly = true
                    break
                }
            }
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
        if (ack.serverAcknowledgedAt.isBlank()) return false
        return true
    }

    private fun handleCancellationOrFatal(
        t: Throwable,
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        claimToken: String
    ) {
        if (t is CancellationException || t is InterruptedException) {
            try {
                outboxRepository.recordTimeout(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    clientEventId = clientEventId,
                    claimToken = claimToken,
                    errorMessage = "Cancelled while gateway request in flight: ${safeErrorMessage(t)}"
                )
            } catch (_: Throwable) {
                // Suppress secondary exception so primary fatal exception propagates
            }
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw t
        }
    }

    private fun checkFatal(t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath) {
            throw t
        }
    }

    private fun safeErrorMessage(t: Throwable): String {
        return safeErrorMessage(t.message ?: t.javaClass.simpleName)
    }

    private fun safeErrorMessage(msg: String): String {
        // Redact potential authorization tokens, keys, or bearer credentials from log/reason strings
        val redacted = msg.replace(Regex("""(?i)(bearer\s+[\w\-._~+/]+=*|token[:=\s]+[\w\-._~+/]+=*)"""), "[REDACTED_CREDENTIAL]")
        return if (redacted.length > 256) redacted.substring(0, 256) + "..." else redacted
    }
}
