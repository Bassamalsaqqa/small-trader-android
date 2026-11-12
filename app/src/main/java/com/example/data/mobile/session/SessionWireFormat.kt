package com.example.data.mobile.session

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString

/**
 * Strict canonical serialization and parsing for staff session records.
 * Uses strict JSON parsing with zero Java Serialization to eliminate object injection vulnerabilities.
 */
internal object SessionWireFormat {

    private val ALLOWED_KEYS = setOf(
        "token",
        "actor_id",
        "device_id",
        "password_epoch",
        "expires_at",
        "created_at",
        "selected_company_id",
        "role_label",
        "actor_name",
        "actor_email"
    )

    fun serialize(record: StaffSessionRecord): ByteArray {
        val map = linkedMapOf<String, Any?>(
            "token" to record.token,
            "actor_id" to record.actorUlid.value,
            "device_id" to record.deviceUuid.value,
            "password_epoch" to record.passwordEpoch,
            "expires_at" to record.expiresAt,
            "created_at" to record.createdAt,
            "selected_company_id" to record.selectedCompanyId?.value,
            "role_label" to record.roleLabel,
            "actor_name" to record.actorName,
            "actor_email" to record.actorEmail
        )
        val jsonStr = CanonicalJsonSerializer.serialize(map)
        return jsonStr.toByteArray(Charsets.UTF_8)
    }

    fun deserialize(bytes: ByteArray): StaffSessionRecord {
        val jsonStr = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw CanonicalSerializationException("Session plaintext is not valid UTF-8")
        }

        val element = StrictJsonParser.parse(jsonStr)
        val obj = element as? StrictJsonObject
            ?: throw CanonicalSerializationException("Session root must be a JSON object")

        for (key in obj.properties.keys) {
            if (key !in ALLOWED_KEYS) {
                throw CanonicalSerializationException("Unknown property in session JSON payload")
            }
        }

        val token = requireString(obj, "token")
        val actorIdStr = requireString(obj, "actor_id")
        val deviceIdStr = requireString(obj, "device_id")
        val passwordEpoch = requireString(obj, "password_epoch")
        val expiresAt = requireString(obj, "expires_at")
        val createdAt = requireString(obj, "created_at")

        val selectedCompanyIdStr = optionalString(obj, "selected_company_id")
        val roleLabel = optionalString(obj, "role_label")
        val actorName = optionalString(obj, "actor_name")
        val actorEmail = optionalString(obj, "actor_email")

        val actorUlid = try {
            ServerUlid.parse(actorIdStr)
        } catch (e: Exception) {
            throw CanonicalSerializationException("Invalid actor_id ULID in session payload")
        }

        val deviceUuid = try {
            ClientUuid.parse(deviceIdStr)
        } catch (e: Exception) {
            throw CanonicalSerializationException("Invalid device_id UUID in session payload")
        }

        val selectedCompanyId = selectedCompanyIdStr?.let {
            try {
                ServerUlid.parse(it)
            } catch (e: Exception) {
                throw CanonicalSerializationException("Invalid selected_company_id ULID in session payload")
            }
        }

        return StaffSessionRecord(
            token = token,
            actorUlid = actorUlid,
            deviceUuid = deviceUuid,
            passwordEpoch = passwordEpoch,
            expiresAt = expiresAt,
            selectedCompanyId = selectedCompanyId,
            roleLabel = roleLabel,
            actorName = actorName,
            actorEmail = actorEmail,
            createdAt = createdAt
        )
    }

    private fun requireString(obj: StrictJsonObject, key: String): String {
        val prop = obj[key] ?: throw CanonicalSerializationException("Missing required property in session payload")
        return (prop as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Invalid property type in session payload")
    }

    private fun optionalString(obj: StrictJsonObject, key: String): String? {
        val prop = obj[key] ?: return null
        if (prop is StrictJsonNull) return null
        return (prop as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Invalid property type in session payload")
    }
}
