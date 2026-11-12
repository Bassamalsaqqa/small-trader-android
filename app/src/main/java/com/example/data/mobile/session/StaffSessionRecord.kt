package com.example.data.mobile.session

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid

/**
 * In-memory representation of an authenticated staff session.
 *
 * Sensitive fields ([token], [passwordEpoch], [actorEmail]) are strictly redacted in [toString]
 * to prevent leaking credentials in logs, crash dumps, or error messages.
 *
 * Role labels (e.g. [roleLabel]) are presentation labels only, NOT authorization grants.
 * Server authorization is always authoritative.
 */
data class StaffSessionRecord(
    val token: String,
    val actorUlid: ServerUlid,
    val deviceUuid: ClientUuid,
    val passwordEpoch: String,
    val expiresAt: String,
    val selectedCompanyId: ServerUlid? = null,
    val roleLabel: String? = null,
    val actorName: String? = null,
    val actorEmail: String? = null,
    val createdAt: String
) {
    init {
        SessionValidation.validateToken(token, "token")
        SessionValidation.validateDateTime(expiresAt, "expires_at")
        SessionValidation.validateDateTime(createdAt, "created_at")
        SessionValidation.validateStringBounds(passwordEpoch, "password_epoch", minLength = 1, maxLength = 128)
        roleLabel?.let { SessionValidation.validateStringBounds(it, "role_label", minLength = 1, maxLength = 255) }
        actorName?.let { SessionValidation.validateStringBounds(it, "actor_name", minLength = 1, maxLength = 255) }
        actorEmail?.let { SessionValidation.validateEmail(it, "actor_email") }
    }

    override fun toString(): String =
        "StaffSessionRecord(token='Bearer [REDACTED]', actorUlid=$actorUlid, " +
                "deviceUuid=$deviceUuid, passwordEpoch='[REDACTED]', expiresAt='$expiresAt', " +
                "selectedCompanyId=$selectedCompanyId, roleLabel=${roleLabel?.let { "'$it'" }}, " +
                "actorName=${actorName?.let { "'$it'" }}, actorEmail='[REDACTED]', createdAt='$createdAt')"
}
