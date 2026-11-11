package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUuid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds

/**
 * Request payload for POST /api/mobile/v1/auth/challenge.
 */
data class AuthChallengeRequest(
    val deviceId: ClientUuid,
    val appVersion: String,
    val platform: String
) {
    init {
        validateStringBounds(appVersion, "app_version", minLength = 1, maxLength = 64)
        require(platform == "android" || platform == "ios") {
            "platform must be 'android' or 'ios', got '$platform'"
        }
    }

    fun toJsonString(): String = CanonicalJsonSerializer.serialize(
        mapOf(
            "device_id" to deviceId.value,
            "app_version" to appVersion,
            "platform" to platform
        )
    )
}

/**
 * Response payload for POST /api/mobile/v1/auth/challenge.
 */
data class AuthChallengeResponse(
    val challengeId: ClientUuid,
    val nonce: String,
    val expiresAt: String
) {
    init {
        validateStringBounds(nonce, "nonce", minLength = 32, maxLength = 128)
        validateDateTime(expiresAt, "expires_at")
    }

    companion object {
        private val ALLOWED_KEYS = setOf("challenge_id", "nonce", "expires_at")

        fun fromStrictJson(obj: StrictJsonObject): AuthChallengeResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "AuthChallengeResponse")
            return AuthChallengeResponse(
                challengeId = requireUuid(obj, "challenge_id"),
                nonce = requireString(obj, "nonce", minLength = 32, maxLength = 128),
                expiresAt = requireString(obj, "expires_at", minLength = 20, maxLength = 35)
            )
        }
    }
}
