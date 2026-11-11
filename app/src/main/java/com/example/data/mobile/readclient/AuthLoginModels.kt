package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.requireArray
import com.example.data.mobile.readclient.ValidationUtils.requireBoolean
import com.example.data.mobile.readclient.ValidationUtils.requireObject
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateEmail
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds

private val MFA_CODE_REGEX = Regex("^[0-9]{6}$")
private val RECOVERY_CODE_REGEX = Regex("^[a-zA-Z0-9]{8,24}(-[a-zA-Z0-9]{8,24})?$")

/**
 * Request payload for POST /api/mobile/v1/auth/login.
 * Sensitive fields (password, mfaCode, recoveryCode) are strictly redacted in toString()
 * to prevent leaking credentials in crash logs, exceptions, or error serialization.
 */
data class AuthLoginRequest(
    val challengeId: ClientUuid,
    val nonce: String,
    val deviceId: ClientUuid,
    val deviceName: String,
    val email: String,
    val password: String,
    val mfaCode: String? = null,
    val recoveryCode: String? = null
) {
    init {
        validateStringBounds(nonce, "nonce", minLength = 32, maxLength = 128)
        validateStringBounds(deviceName, "device_name", minLength = 1, maxLength = 100)
        validateEmail(email, "email")
        validateStringBounds(password, "password", minLength = 1, maxLength = 256)

        if (mfaCode != null) {
            require(MFA_CODE_REGEX.matches(mfaCode)) {
                "mfa_code must be 6 digits, got '$mfaCode'"
            }
        }
        if (recoveryCode != null) {
            require(RECOVERY_CODE_REGEX.matches(recoveryCode)) {
                "recovery_code must match Fortify recovery format, got '$recoveryCode'"
            }
        }
        require(mfaCode == null || recoveryCode == null) {
            "mfa_code and recovery_code are mutually exclusive; submitting both simultaneously is rejected"
        }
    }

    override fun toString(): String =
        "AuthLoginRequest(challengeId=$challengeId, nonce='$nonce', deviceId=$deviceId, " +
                "deviceName='$deviceName', email='$email', password='[REDACTED]', " +
                "mfaCode=${if (mfaCode != null) "'[REDACTED]'" else "null"}, " +
                "recoveryCode=${if (recoveryCode != null) "'[REDACTED]'" else "null"})"

    fun toJsonString(): String {
        val map = linkedMapOf<String, Any?>(
            "challenge_id" to challengeId.value,
            "nonce" to nonce,
            "device_id" to deviceId.value,
            "device_name" to deviceName,
            "email" to email,
            "password" to password,
            "mfa_code" to mfaCode,
            "recovery_code" to recoveryCode
        )
        return CanonicalJsonSerializer.serialize(map)
    }
}

/**
 * Staff actor profile embedded in successful login response.
 * Role is an informative presentation label only; never an authorization grant.
 */
data class ActorProfile(
    val id: ServerUlid,
    val name: String,
    val email: String,
    val role: String,
    val passwordEpoch: String
) {
    init {
        validateStringBounds(name, "actor.name", minLength = 1, maxLength = 255)
        validateEmail(email, "actor.email")
        validateStringBounds(role, "actor.role", minLength = 1, maxLength = 255)
        validateStringBounds(passwordEpoch, "actor.password_epoch", minLength = 1, maxLength = 128)
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "name", "email", "role", "password_epoch")

        fun fromStrictJson(obj: StrictJsonObject): ActorProfile {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ActorProfile")
            return ActorProfile(
                id = requireUlid(obj, "id"),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                email = requireString(obj, "email", minLength = 3, maxLength = 255),
                role = requireString(obj, "role", minLength = 1, maxLength = 255),
                passwordEpoch = requireString(obj, "password_epoch", minLength = 1, maxLength = 128)
            )
        }
    }
}

/**
 * Company membership returned on login.
 */
data class CompanyMembership(
    val id: ServerUlid,
    val name: String,
    val baseCurrency: String,
    val isDefault: Boolean
) {
    init {
        validateStringBounds(name, "company.name", minLength = 1, maxLength = 255)
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(baseCurrency)) {
            "base_currency must be 3-letter ISO code, got '$baseCurrency'"
        }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "name", "base_currency", "is_default")

        fun fromStrictJson(obj: StrictJsonObject): CompanyMembership {
            checkAllowedKeys(obj, ALLOWED_KEYS, "CompanyMembership")
            return CompanyMembership(
                id = requireUlid(obj, "id"),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                baseCurrency = requireString(obj, "base_currency", minLength = 3, maxLength = 3),
                isDefault = requireBoolean(obj, "is_default")
            )
        }
    }
}

/**
 * Successful authentication response.
 * Bearer token is strictly redacted in toString().
 */
data class AuthLoginSuccessResponse(
    val token: String,
    val tokenType: String,
    val expiresAt: String,
    val actor: ActorProfile,
    val companies: List<CompanyMembership>
) {
    init {
        validateStringBounds(token, "token", minLength = 20, maxLength = 2048)
        require(tokenType == "Bearer") {
            "token_type must be 'Bearer', got '$tokenType'"
        }
        validateDateTime(expiresAt, "expires_at")
        require(companies.isNotEmpty()) {
            "companies must contain at least one company membership"
        }
    }

    override fun toString(): String =
        "AuthLoginSuccessResponse(token='Bearer [REDACTED]', tokenType='$tokenType', " +
                "expiresAt='$expiresAt', actor=$actor, companies=$companies)"

    companion object {
        private val ALLOWED_KEYS = setOf("token", "token_type", "expires_at", "actor", "companies")

        fun fromStrictJson(obj: StrictJsonObject): AuthLoginSuccessResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "AuthLoginSuccessResponse")
            val companiesArray = requireArray(obj, "companies")
            val companies = (0 until companiesArray.size).map { i ->
                val compObj = companiesArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("companies[$i] must be a JSON object")
                CompanyMembership.fromStrictJson(compObj)
            }
            return AuthLoginSuccessResponse(
                token = requireString(obj, "token", minLength = 20, maxLength = 2048),
                tokenType = requireString(obj, "token_type", minLength = 6, maxLength = 6),
                expiresAt = requireString(obj, "expires_at", minLength = 20, maxLength = 35),
                actor = ActorProfile.fromStrictJson(requireObject(obj, "actor")),
                companies = companies
            )
        }
    }
}

/**
 * MFA_REQUIRED response indicating the caller must perform a fresh challenge retry.
 * Nonce-less continuation or reusing the consumed challenge is strictly forbidden.
 */
data class MfaRequiredResponse(
    val status: String = "MFA_REQUIRED",
    val retry: String = "fresh_challenge"
) {
    init {
        require(status == "MFA_REQUIRED") { "status must be 'MFA_REQUIRED', got '$status'" }
        require(retry == "fresh_challenge") { "retry must be 'fresh_challenge', got '$retry'" }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("status", "retry")

        fun fromStrictJson(obj: StrictJsonObject): MfaRequiredResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "MfaRequiredResponse")
            val status = requireString(obj, "status", minLength = 12, maxLength = 12)
            val retry = requireString(obj, "retry", minLength = 15, maxLength = 15)
            return MfaRequiredResponse(status = status, retry = retry)
        }
    }
}

/**
 * Result of auth login: either full Success or MFA_REQUIRED fresh_challenge retry.
 */
sealed interface AuthLoginResult {
    data class Success(val response: AuthLoginSuccessResponse) : AuthLoginResult
    data class MfaRequired(val response: MfaRequiredResponse) : AuthLoginResult

    companion object {
        fun fromStrictJson(obj: StrictJsonObject): AuthLoginResult {
            if (obj.properties.containsKey("status") || obj.properties.containsKey("retry")) {
                return MfaRequired(MfaRequiredResponse.fromStrictJson(obj))
            }
            return Success(AuthLoginSuccessResponse.fromStrictJson(obj))
        }
    }
}
