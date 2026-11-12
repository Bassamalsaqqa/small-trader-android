package com.example.data.mobile.integration

import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.session.AuthDenialListener
import com.example.data.mobile.session.AuthDenialScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Minimal runtime bridge composing [com.example.data.mobile.session.StaffSessionCoordinator]
 * [AuthDenialListener] with [OutboxRepository.lockQueueOnAuthDenied].
 *
 * Guarantees:
 * 1. Exact scope binding: locks only the immutable (companyId, actorId, deviceId) from the accepted 401 event.
 * 2. Persists lock even on empty queue via outbox scope lock table.
 * 3. Preserves payload bytes, digests, and records; performs no record deletion, queue unlock, or capture.
 * 4. Room database operations execute off-main to prevent Room main thread violation.
 * 5. Exceptions during notification propagate to coordinator so they surface as typed locked UI.
 * 6. Never automatically unlocks queue on subsequent login or read success.
 */
class OutboxAuthDenialBridge(
    private val outboxRepository: OutboxRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuthDenialListener {

    override fun onAuthDenied(scope: AuthDenialScope) {
        val isMainThread = try {
            val looper = android.os.Looper.myLooper()
            looper != null && looper == android.os.Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }

        if (isMainThread) {
            runBlocking(ioDispatcher) {
                outboxRepository.lockQueueOnAuthDenied(
                    companyId = scope.companyId,
                    actorId = scope.actorId,
                    deviceId = scope.deviceId,
                    reason = "HTTP 401 AuthDenied at ${scope.deniedAt}"
                )
            }
        } else {
            outboxRepository.lockQueueOnAuthDenied(
                companyId = scope.companyId,
                actorId = scope.actorId,
                deviceId = scope.deviceId,
                reason = "HTTP 401 AuthDenied at ${scope.deniedAt}"
            )
        }
    }
}
