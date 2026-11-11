package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUuid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime

/**
 * Request payload for POST /api/mobile/v1/auth/logout.
 */
data class AuthLogoutRequest(
    val deviceId: ClientUuid
) {
    fun toJsonString(): String = CanonicalJsonSerializer.serialize(
        mapOf("device_id" to deviceId.value)
    )
}

/**
 * Response payload for POST /api/mobile/v1/auth/logout.
 */
data class AuthLogoutResponse(
    val status: String,
    val revokedAt: String
) {
    init {
        require(status == "logged_out") {
            "status must be 'logged_out', got '$status'"
        }
        validateDateTime(revokedAt, "revoked_at")
    }

    companion object {
        private val ALLOWED_KEYS = setOf("status", "revoked_at")

        fun fromStrictJson(obj: StrictJsonObject): AuthLogoutResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "AuthLogoutResponse")
            return AuthLogoutResponse(
                status = requireString(obj, "status", minLength = 10, maxLength = 10),
                revokedAt = requireString(obj, "revoked_at", minLength = 20, maxLength = 35)
            )
        }
    }
}
