package com.example.data

import com.example.model.Product
import com.example.model.ProductCategory
import java.util.Locale

/**
 * Shared product search and ranking primitive for Small Trader (التاجر الصغير).
 *
 * Single authoritative search implementation shared across:
 * 1. [TraderRepository.searchProducts]
 * 2. [com.example.viewmodel.TraderUiState.filteredProducts]
 * 3. [com.example.ui.screens.ReceivePurchaseBillDialog] item picker
 * 4. JVM benchmark performance regression tests
 */
object ProductSearch {

    /**
     * Pure, symmetric text normalizer:
     * - Maps Eastern Arabic digits (٠-٩) and Persian digits (۰-۹) to ASCII digits (0-9).
     * - Folds Arabic Alef variants (أ, إ, آ, ٱ) -> ا.
     * - Folds Taa Marbuta (ة) -> ه.
     * - Folds Alef Maqsura (ى) -> ي.
     * - Strips Harakat / diacritics (\u064B-\u065F, \u0670) and Tatweel (\u0640).
     * - Converts ASCII characters to lowercase.
     * - Trims surrounding whitespace.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> { /* strip diacritics & tatweel */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Multi-tier relevance scoring:
     * - Tier 1: Exact barcode match (1000)
     * - Tier 2: Prefix barcode match (800)
     * - Tier 3: Exact name match (AR or EN) (700)
     * - Tier 4: Prefix name match (AR or EN) (600)
     * - Tier 5: Word boundary match in name (" $normalizedQuery") (500)
     * - Tier 6: Substring match in name (400)
     * - Tier 7: Token match in unit, category label, or search tokens (200)
     *
     * Note on SKU vs ID: The Product model has barcode and internal id, but NO real SKU.
     * Internal id is NEVER matched as an SKU.
     */
    fun computeScore(product: Product, normalizedQuery: String): Int {
        if (normalizedQuery.isEmpty()) return 0
        if (product.normalizedBarcode == normalizedQuery) return 1000
        if (product.normalizedBarcode.startsWith(normalizedQuery)) return 800
        if (product.normalizedNameAr == normalizedQuery || product.normalizedNameEn == normalizedQuery) return 700
        if (product.normalizedNameAr.startsWith(normalizedQuery) || product.normalizedNameEn.startsWith(normalizedQuery)) return 600
        if (product.normalizedNameAr.contains(" $normalizedQuery") || product.normalizedNameEn.contains(" $normalizedQuery")) return 500
        if (product.normalizedNameAr.contains(normalizedQuery) || product.normalizedNameEn.contains(normalizedQuery)) return 400
        if (product.normalizedSearchTokens.contains(normalizedQuery)) return 200
        return 0
    }

    /**
     * Shared product search primitive.
     *
     * Invariants:
     * - Public result limit is strictly clamped to max 30 candidates.
     * - Defined handling for limit <= 0: returns emptyList().
     * - Filter criteria (category and stockTrackedOnly) apply across ALL tiers, including exact barcode.
     * - Stable, deterministic tie-breaking preserves original collection index.
     * - No active flag check (Product model has no active property; active filtering is not invented).
     */
    fun search(
        products: List<Product>,
        query: String,
        category: ProductCategory = ProductCategory.ALL,
        limit: Int = 30,
        stockTrackedOnly: Boolean = false
    ): List<Product> {
        val clampedLimit = if (limit <= 0) 0 else limit.coerceAtMost(30)
        if (clampedLimit == 0 || products.isEmpty()) return emptyList()

        val q = normalize(query)

        // Pre-filter: category & stock-tracked constraints apply across all tiers
        val filtered = products.filter { prod ->
            val matchesCategory = category == ProductCategory.ALL || prod.category == category
            val matchesTracking = !stockTrackedOnly || prod.isStockTracked
            matchesCategory && matchesTracking
        }

        if (q.isEmpty()) {
            return filtered.take(clampedLimit)
        }

        // Tiered scoring with deterministic tie-breaking (score descending, then original index ascending)
        return filtered
            .mapIndexedNotNull { idx, prod ->
                val score = computeScore(prod, q)
                if (score > 0) ScoredProduct(prod, score, idx) else null
            }
            .sortedWith(
                compareByDescending<ScoredProduct> { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.product }
            .take(clampedLimit)
    }

    private data class ScoredProduct(
        val product: Product,
        val score: Int,
        val originalIndex: Int
    )

    fun countMatches(
        products: List<Product>,
        query: String,
        category: ProductCategory = ProductCategory.ALL,
        stockTrackedOnly: Boolean = false
    ): Int {
        val q = normalize(query)
        val filtered = products.filter { prod ->
            val matchesCategory = category == ProductCategory.ALL || prod.category == category
            val matchesTracking = !stockTrackedOnly || prod.isStockTracked
            matchesCategory && matchesTracking
        }
        if (q.isEmpty()) return filtered.size
        return filtered.count { computeScore(it, q) > 0 }
    }
}
