package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.requireArray
import com.example.data.mobile.readclient.ValidationUtils.requireBoolean
import com.example.data.mobile.readclient.ValidationUtils.requireNullableString
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds
import java.util.Collections

/**
 * Customer entity in customer directory response.
 * Redacts personal customer name and phone in toString() representation.
 */
data class CustomerDirectoryItem(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val phone: String?,
    val isWalkIn: Boolean
) {
    init {
        validateStringBounds(code, "customer.code", minLength = 1, maxLength = 64)
        validateStringBounds(name, "customer.name", minLength = 1, maxLength = 255)
        if (phone != null) {
            validateStringBounds(phone, "customer.phone", minLength = 1, maxLength = 64)
        }
    }

    override fun toString(): String =
        "CustomerDirectoryItem(id=$id, code='$code', name='[REDACTED]', phone='[REDACTED]', isWalkIn=$isWalkIn)"

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "phone", "is_walk_in")

        fun fromStrictJson(obj: StrictJsonObject): CustomerDirectoryItem {
            checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerDirectoryItem")
            return CustomerDirectoryItem(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 64),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                phone = optionalString(obj, "phone", minLength = 1, maxLength = 64),
                isWalkIn = requireBoolean(obj, "is_walk_in")
            )
        }
    }
}

/**
 * Authoritative response payload for GET /api/mobile/v1/companies/{companyId}/customers.
 * Defensive copy protects customers list from external modification.
 */
class CustomerDirectoryResponse(
    val companyId: ServerUlid,
    val generatedAt: String,
    val nextCursor: String?,
    val hasMore: Boolean,
    customers: List<CustomerDirectoryItem>
) {
    val customers: List<CustomerDirectoryItem> = Collections.unmodifiableList(customers.toList())

    init {
        validateDateTime(generatedAt, "generated_at")
        if (nextCursor != null) {
            validateStringBounds(nextCursor, "next_cursor", minLength = 1, maxLength = 255)
        }
    }

    fun copy(
        companyId: ServerUlid = this.companyId,
        generatedAt: String = this.generatedAt,
        nextCursor: String? = this.nextCursor,
        hasMore: Boolean = this.hasMore,
        customers: List<CustomerDirectoryItem> = this.customers
    ): CustomerDirectoryResponse = CustomerDirectoryResponse(
        companyId, generatedAt, nextCursor, hasMore, customers
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomerDirectoryResponse) return false
        return companyId == other.companyId &&
                generatedAt == other.generatedAt &&
                nextCursor == other.nextCursor &&
                hasMore == other.hasMore &&
                customers == other.customers
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + generatedAt.hashCode()
        result = 31 * result + (nextCursor?.hashCode() ?: 0)
        result = 31 * result + hasMore.hashCode()
        result = 31 * result + customers.hashCode()
        return result
    }

    override fun toString(): String =
        "CustomerDirectoryResponse(companyId=$companyId, generatedAt='$generatedAt', nextCursor='[REDACTED]', " +
                "hasMore=$hasMore, customersCount=${customers.size})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "generated_at", "next_cursor", "has_more", "customers"
        )

        fun fromStrictJson(obj: StrictJsonObject): CustomerDirectoryResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerDirectoryResponse")

            val customersArray = requireArray(obj, "customers")
            val customers = (0 until customersArray.size).map { i ->
                val cObj = customersArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("customers[$i] must be a JSON object")
                CustomerDirectoryItem.fromStrictJson(cObj)
            }

            return CustomerDirectoryResponse(
                companyId = requireUlid(obj, "company_id"),
                generatedAt = requireString(obj, "generated_at", minLength = 20, maxLength = 35),
                nextCursor = requireNullableString(obj, "next_cursor", minLength = 1, maxLength = 255),
                hasMore = requireBoolean(obj, "has_more"),
                customers = customers
            )
        }
    }
}
