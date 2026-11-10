package com.example.data.mobile.contract

import java.security.MessageDigest
import java.util.UUID

/**
 * Strict 26-character Crockford Base32 Universally Unique Lexicographically Sortable Identifier (ULID)
 * for server-side domain entities (company_id, actor_id, product_id, customer_id, warehouse_id,
 * cash_account_id, unit_id, product_unit_id, invoice_id, payment_id).
 *
 * Matching regex: ^[0-7][0-9A-HJKMNP-TV-Z]{25}$
 * Rejects lowercase characters, 'I', 'L', 'O', 'U', leading character outside [0-7], and UUIDs.
 */
data class ServerUlid(val value: String) : Comparable<ServerUlid> {
    init {
        require(ContractConstants.CROCKFORD_ULID_REGEX.matches(value)) {
            "Invalid ServerUlid: '$value'. Must be a 26-character Crockford Base32 ULID starting with [0-7] (e.g. 01JCV8X9M2NQW3TYZ4R5S6789A)"
        }
    }

    override fun toString(): String = value

    override fun compareTo(other: ServerUlid): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: String): ServerUlid = ServerUlid(raw)

        fun isValid(raw: String?): Boolean =
            raw != null && ContractConstants.CROCKFORD_ULID_REGEX.matches(raw)
    }
}

/**
 * Client-side and ephemeral RFC 4122 UUID identifier (client_event_id, device_id, device_lease_id, challenge_id).
 *
 * Matching regex: ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$
 */
data class ClientUuid(val value: String) : Comparable<ClientUuid> {
    init {
        require(ContractConstants.CLIENT_UUID_REGEX.matches(value)) {
            "Invalid ClientUuid: '$value'. Must be an RFC 4122 UUID (e.g. 3fa85f64-5717-4562-b3fc-2c963f66afa6)"
        }
    }

    override fun toString(): String = value

    override fun compareTo(other: ClientUuid): Int = value.compareTo(other.value, ignoreCase = true)

    companion object {
        fun random(): ClientUuid = ClientUuid(UUID.randomUUID().toString())

        fun parse(raw: String): ClientUuid = ClientUuid(raw)

        fun isValid(raw: String?): Boolean =
            raw != null && ContractConstants.CLIENT_UUID_REGEX.matches(raw)
    }
}

/**
 * Cryptographic SHA-256 payload digest formatted as 'sha256:<64 lowercase hex characters>'.
 */
data class PayloadDigest(val value: String) : Comparable<PayloadDigest> {
    init {
        require(ContractConstants.SHA256_DIGEST_REGEX.matches(value)) {
            "Invalid PayloadDigest: '$value'. Must match 'sha256:<64 lowercase hex characters>'"
        }
    }

    val hexString: String
        get() = value.removePrefix("sha256:")

    override fun toString(): String = value

    override fun compareTo(other: PayloadDigest): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: String): PayloadDigest = PayloadDigest(raw)

        fun fromBytes(bytes: ByteArray): PayloadDigest {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(bytes)
            val hex = hash.joinToString("") { "%02x".format(it) }
            return PayloadDigest("sha256:$hex")
        }

        fun fromUtf8String(str: String): PayloadDigest {
            return fromBytes(str.toByteArray(Charsets.UTF_8))
        }

        fun isValid(raw: String?): Boolean =
            raw != null && ContractConstants.SHA256_DIGEST_REGEX.matches(raw)
    }
}
