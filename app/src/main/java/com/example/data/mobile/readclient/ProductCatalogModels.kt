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
import com.example.data.mobile.readclient.ValidationUtils.optionalTaxPercentage
import com.example.data.mobile.readclient.ValidationUtils.optionalUlid
import com.example.data.mobile.readclient.ValidationUtils.requireArray
import com.example.data.mobile.readclient.ValidationUtils.requireBoolean
import com.example.data.mobile.readclient.ValidationUtils.requireMoney
import com.example.data.mobile.readclient.ValidationUtils.requireNonnegativeQuantity
import com.example.data.mobile.readclient.ValidationUtils.requireObject
import com.example.data.mobile.readclient.ValidationUtils.requirePositiveQuantity
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds

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
 */
data class ProductDefaultUnit(
    val unitId: ServerUlid,
    val productUnitId: ServerUlid,
    val conversionToBase: PositiveDecimalQuantity,
    val salePrice: MoneyAmount?
) {
    companion object {
        private val ALLOWED_KEYS = setOf("unit_id", "product_unit_id", "conversion_to_base", "sale_price")

        fun fromStrictJson(obj: StrictJsonObject): ProductDefaultUnit {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductDefaultUnit")
            return ProductDefaultUnit(
                unitId = requireUlid(obj, "unit_id"),
                productUnitId = requireUlid(obj, "product_unit_id"),
                conversionToBase = requirePositiveQuantity(obj, "conversion_to_base"),
                salePrice = optionalMoney(obj, "sale_price")
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
 */
data class ProductCatalogItem(
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
    val units: List<ProductPackagingUnit>
) {
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

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "sku", "barcode", "name", "unit_price", "tax_rate",
            "tax_rate_id", "is_tracked", "available_quantity",
            "base_unit", "default_unit", "units"
        )

        fun fromStrictJson(obj: StrictJsonObject): ProductCatalogItem {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ProductCatalogItem")

            val taxRateId = optionalUlid(obj, "tax_rate_id")
            val taxRate = optionalTaxPercentage(obj, "tax_rate")

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
 */
data class ProductCatalogResponse(
    val companyId: ServerUlid,
    val catalogSnapshotVersion: String,
    val generatedAt: String,
    val nextCursor: String?,
    val hasMore: Boolean,
    val products: List<ProductCatalogItem>
) {
    init {
        validateStringBounds(catalogSnapshotVersion, "catalog_snapshot_version", minLength = 1, maxLength = 64)
        validateDateTime(generatedAt, "generated_at")
        if (nextCursor != null) {
            validateStringBounds(nextCursor, "next_cursor", minLength = 1, maxLength = 255)
        }
    }

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
                nextCursor = optionalString(obj, "next_cursor", minLength = 1, maxLength = 255),
                hasMore = requireBoolean(obj, "has_more"),
                products = products
            )
        }
    }
}
