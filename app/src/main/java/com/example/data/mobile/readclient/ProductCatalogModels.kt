package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalMoney
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.requireArray
import com.example.data.mobile.readclient.ValidationUtils.requireBoolean
import com.example.data.mobile.readclient.ValidationUtils.requireMoney
import com.example.data.mobile.readclient.ValidationUtils.requireNullableMoney
import com.example.data.mobile.readclient.ValidationUtils.requireNullableString
import com.example.data.mobile.readclient.ValidationUtils.requireNullableTaxPercentage
import com.example.data.mobile.readclient.ValidationUtils.requireNullableUlid
import com.example.data.mobile.readclient.ValidationUtils.requireNonnegativeQuantity
import com.example.data.mobile.readclient.ValidationUtils.requireObject
import com.example.data.mobile.readclient.ValidationUtils.requirePositiveQuantity
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds
import java.math.BigDecimal
import java.util.Collections

data class ProductBaseUnit(
    val id: ServerUlid,
    val code: String,
    val name: String
) {
    init {
        validateStringBounds(code, "base_unit.code", minLength = 1, maxLength = 32)
        validateStringBounds(name, "base_unit.name", minLength = 1, maxLength = 128)
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name")

        fun fromStrictJson(obj: StrictJsonObject): ProductBaseUnit {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductBaseUnit")
            return ProductBaseUnit(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 32),
                name = requireString(obj, "name", minLength = 1, maxLength = 128)
            )
        }
    }
}

/**
 * Default sales unit for product.
 * NOTE: Per revision 2 decisions, default_unit DTO has no name field.
 * Direct constructor and copy invariants ensure conversion is positive and salePrice non-negative.
 */
data class ProductDefaultUnit(
    val unitId: ServerUlid,
    val productUnitId: ServerUlid,
    val conversionToBase: PositiveDecimalQuantity,
    val salePrice: MoneyAmount?
) {
    init {
        require(conversionToBase.value > BigDecimal.ZERO) {
            "conversion_to_base must be strictly positive"
        }
        if (salePrice != null) {
            require(salePrice.value >= BigDecimal.ZERO) {
                "sale_price must be non-negative"
            }
        }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("unit_id", "product_unit_id", "conversion_to_base", "sale_price")

        fun fromStrictJson(obj: StrictJsonObject): ProductDefaultUnit {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductDefaultUnit")
            return ProductDefaultUnit(
                unitId = requireUlid(obj, "unit_id"),
                productUnitId = requireUlid(obj, "product_unit_id"),
                conversionToBase = requirePositiveQuantity(obj, "conversion_to_base"),
                salePrice = requireNullableMoney(obj, "sale_price")
            )
        }
    }
}

data class ProductPackagingUnit(
    val productUnitId: ServerUlid,
    val unitId: ServerUlid,
    val code: String,
    val name: String,
    val conversionToBase: PositiveDecimalQuantity,
    val isBase: Boolean,
    val isDefaultSale: Boolean,
    val salePrice: MoneyAmount?
) {
    init {
        validateStringBounds(code, "packaging_unit.code", minLength = 1, maxLength = 32)
        validateStringBounds(name, "packaging_unit.name", minLength = 1, maxLength = 128)
    }

    companion object {
        private val ALLOWED_KEYS = setOf(
            "product_unit_id", "unit_id", "code", "name",
            "conversion_to_base", "is_base", "is_default_sale", "sale_price"
        )

        fun fromStrictJson(obj: StrictJsonObject): ProductPackagingUnit {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductPackagingUnit")
            return ProductPackagingUnit(
                productUnitId = requireUlid(obj, "product_unit_id"),
                unitId = requireUlid(obj, "unit_id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 32),
                name = requireString(obj, "name", minLength = 1, maxLength = 128),
                conversionToBase = requirePositiveQuantity(obj, "conversion_to_base"),
                isBase = requireBoolean(obj, "is_base"),
                isDefaultSale = requireBoolean(obj, "is_default_sale"),
                salePrice = optionalMoney(obj, "sale_price")
            )
        }
    }
}

/**
 * Product item in catalog response.
 * Enforces truthful nullable paired product tax association:
 * (taxRateId == null && taxRate == null) || (taxRateId != null && taxRate != null).
 * Null pair signifies no product default tax association, NEVER an inferred zero tax or exemption.
 * Defensive copy protects packaging units list from external modification.
 */
class ProductCatalogItem(
    val id: ServerUlid,
    val sku: String,
    val barcode: String?,
    val name: String,
    val unitPrice: MoneyAmount,
    val taxRate: TaxPercentage?,
    val taxRateId: ServerUlid?,
    val isTracked: Boolean,
    val availableQuantity: NonnegativeDecimalQuantity,
    val baseUnit: ProductBaseUnit,
    val defaultUnit: ProductDefaultUnit,
    units: List<ProductPackagingUnit>
) {
    val units: List<ProductPackagingUnit> = Collections.unmodifiableList(units.toList())

    init {
        validateStringBounds(sku, "product.sku", minLength = 1, maxLength = 128)
        if (barcode != null) {
            validateStringBounds(barcode, "product.barcode", minLength = 1, maxLength = 128)
        }
        validateStringBounds(name, "product.name", minLength = 1, maxLength = 255)

        // Strict paired tax rate rule
        if (taxRateId == null) {
            require(taxRate == null) {
                "tax_rate must be null when tax_rate_id is null; null pair means no product default tax association, not zero tax"
            }
        } else {
            require(taxRate != null) {
                "tax_rate cannot be null when tax_rate_id is present ($taxRateId)"
            }
        }
    }

    fun copy(
        id: ServerUlid = this.id,
        sku: String = this.sku,
        barcode: String? = this.barcode,
        name: String = this.name,
        unitPrice: MoneyAmount = this.unitPrice,
        taxRate: TaxPercentage? = this.taxRate,
        taxRateId: ServerUlid? = this.taxRateId,
        isTracked: Boolean = this.isTracked,
        availableQuantity: NonnegativeDecimalQuantity = this.availableQuantity,
        baseUnit: ProductBaseUnit = this.baseUnit,
        defaultUnit: ProductDefaultUnit = this.defaultUnit,
        units: List<ProductPackagingUnit> = this.units
    ): ProductCatalogItem = ProductCatalogItem(
        id, sku, barcode, name, unitPrice, taxRate, taxRateId,
        isTracked, availableQuantity, baseUnit, defaultUnit, units
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductCatalogItem) return false
        return id == other.id &&
                sku == other.sku &&
                barcode == other.barcode &&
                name == other.name &&
                unitPrice == other.unitPrice &&
                taxRate == other.taxRate &&
                taxRateId == other.taxRateId &&
                isTracked == other.isTracked &&
                availableQuantity == other.availableQuantity &&
                baseUnit == other.baseUnit &&
                defaultUnit == other.defaultUnit &&
                units == other.units
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sku.hashCode()
        result = 31 * result + (barcode?.hashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + unitPrice.hashCode()
        result = 31 * result + (taxRate?.hashCode() ?: 0)
        result = 31 * result + (taxRateId?.hashCode() ?: 0)
        result = 31 * result + isTracked.hashCode()
        result = 31 * result + availableQuantity.hashCode()
        result = 31 * result + baseUnit.hashCode()
        result = 31 * result + defaultUnit.hashCode()
        result = 31 * result + units.hashCode()
        return result
    }

    override fun toString(): String =
        "ProductCatalogItem(id=$id, sku='$sku', barcode=${if (barcode != null) "'$barcode'" else "null"}, " +
                "name='[REDACTED]', unitPrice=$unitPrice, taxRate=$taxRate, taxRateId=$taxRateId, " +
                "isTracked=$isTracked, availableQuantity=$availableQuantity, baseUnit=$baseUnit, " +
                "defaultUnit=$defaultUnit, unitsCount=${units.size})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "sku", "barcode", "name", "unit_price", "tax_rate",
            "tax_rate_id", "is_tracked", "available_quantity",
            "base_unit", "default_unit", "units"
        )

        fun fromStrictJson(obj: StrictJsonObject): ProductCatalogItem {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductCatalogItem")

            val taxRateId = requireNullableUlid(obj, "tax_rate_id")
            val taxRate = requireNullableTaxPercentage(obj, "tax_rate")

            if (taxRateId == null && taxRate != null) {
                throw CanonicalSerializationException("tax_rate must be null when tax_rate_id is null")
            }
            if (taxRateId != null && taxRate == null) {
                throw CanonicalSerializationException("tax_rate must be non-null when tax_rate_id is provided")
            }

            val unitsArray = requireArray(obj, "units")
            val units = (0 until unitsArray.size).map { i ->
                val uObj = unitsArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("units[$i] must be a JSON object")
                ProductPackagingUnit.fromStrictJson(uObj)
            }

            return ProductCatalogItem(
                id = requireUlid(obj, "id"),
                sku = requireString(obj, "sku", minLength = 1, maxLength = 128),
                barcode = optionalString(obj, "barcode", minLength = 1, maxLength = 128),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                unitPrice = requireMoney(obj, "unit_price"),
                taxRate = taxRate,
                taxRateId = taxRateId,
                isTracked = requireBoolean(obj, "is_tracked"),
                availableQuantity = requireNonnegativeQuantity(obj, "available_quantity"),
                baseUnit = ProductBaseUnit.fromStrictJson(requireObject(obj, "base_unit")),
                defaultUnit = ProductDefaultUnit.fromStrictJson(requireObject(obj, "default_unit")),
                units = units
            )
        }
    }
}

/**
 * Authoritative response payload for GET /api/mobile/v1/companies/{companyId}/products.
 * Defensive copy protects products list from external modification.
 */
class ProductCatalogResponse(
    val companyId: ServerUlid,
    val catalogSnapshotVersion: String,
    val generatedAt: String,
    val nextCursor: String?,
    val hasMore: Boolean,
    products: List<ProductCatalogItem>
) {
    val products: List<ProductCatalogItem> = Collections.unmodifiableList(products.toList())

    init {
        validateStringBounds(catalogSnapshotVersion, "catalog_snapshot_version", minLength = 1, maxLength = 64)
        validateDateTime(generatedAt, "generated_at")
        if (nextCursor != null) {
            validateStringBounds(nextCursor, "next_cursor", minLength = 1, maxLength = 255)
        }
    }

    fun copy(
        companyId: ServerUlid = this.companyId,
        catalogSnapshotVersion: String = this.catalogSnapshotVersion,
        generatedAt: String = this.generatedAt,
        nextCursor: String? = this.nextCursor,
        hasMore: Boolean = this.hasMore,
        products: List<ProductCatalogItem> = this.products
    ): ProductCatalogResponse = ProductCatalogResponse(
        companyId, catalogSnapshotVersion, generatedAt, nextCursor, hasMore, products
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductCatalogResponse) return false
        return companyId == other.companyId &&
                catalogSnapshotVersion == other.catalogSnapshotVersion &&
                generatedAt == other.generatedAt &&
                nextCursor == other.nextCursor &&
                hasMore == other.hasMore &&
                products == other.products
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + catalogSnapshotVersion.hashCode()
        result = 31 * result + generatedAt.hashCode()
        result = 31 * result + (nextCursor?.hashCode() ?: 0)
        result = 31 * result + hasMore.hashCode()
        result = 31 * result + products.hashCode()
        return result
    }

    override fun toString(): String =
        "ProductCatalogResponse(companyId=$companyId, catalogSnapshotVersion='$catalogSnapshotVersion', " +
                "generatedAt='$generatedAt', nextCursor='[REDACTED]', hasMore=$hasMore, productsCount=${products.size})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "catalog_snapshot_version", "generated_at",
            "next_cursor", "has_more", "products"
        )

        fun fromStrictJson(obj: StrictJsonObject): ProductCatalogResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductCatalogResponse")

            val productsArray = requireArray(obj, "products")
            val products = (0 until productsArray.size).map { i ->
                val pObj = productsArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("products[$i] must be a JSON object")
                ProductCatalogItem.fromStrictJson(pObj)
            }

            return ProductCatalogResponse(
                companyId = requireUlid(obj, "company_id"),
                catalogSnapshotVersion = requireString(obj, "catalog_snapshot_version", minLength = 1, maxLength = 64),
                generatedAt = requireString(obj, "generated_at", minLength = 20, maxLength = 35),
                nextCursor = requireNullableString(obj, "next_cursor", minLength = 1, maxLength = 255),
                hasMore = requireBoolean(obj, "has_more"),
                products = products
            )
        }
    }
}
