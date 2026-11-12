package com.example.data.mobile.integration

import android.content.Context
import android.os.Looper
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.outbox.OutboxRepositoryImpl
import com.example.data.mobile.session.AuthDenialListener
import com.example.data.mobile.session.AuthDenialScope

/** Persists accepted denial events without deleting records or unlocking any scope. */
class OutboxAuthDenialBridge(
    private val repository: () -> OutboxRepository
) : AuthDenialListener {
    constructor(outboxRepository: OutboxRepository) : this({ outboxRepository })

    override fun onAuthDenied(scope: AuthDenialScope) {
        // The synchronous listener is called by the coordinator's IO operation. Reject misuse
        // rather than blocking the UI while another dispatcher writes to Room.
        check(Looper.myLooper() != Looper.getMainLooper()) { "Auth denial persistence requires a background thread" }
        repository().lockQueueOnAuthDenied(
            companyId = scope.companyId,
            actorId = scope.actorId,
            deviceId = scope.deviceId,
            reason = "AUTH_DENIED"
        )
    }

    companion object {
        /** Opens Room only on the first background denial; failures reach the coordinator. */
        fun create(context: Context): OutboxAuthDenialBridge {
            val applicationContext = context.applicationContext
            val repository by lazy {
                OutboxRepositoryImpl(OutboxDatabase.buildDatabase(applicationContext))
            }
            return OutboxAuthDenialBridge { repository }
        }
    }
}
