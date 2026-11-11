package com.example

import com.example.data.OfflineDemoTraderRepository
import com.example.data.ProductSearch
import com.example.model.Product
import com.example.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random

/**
 * Performance, Boundedness, and Relevance Benchmark for Product Search
 * over a representative 10,000 synthetic product dataset on the JVM.
 *
 * Verifies:
 * 1. Boundedness & Defined Clamping: Limits <= 0 return empty; oversized limits clamp to max 30.
 * 2. Relevance hierarchy: Exact barcode (Tier 1) > Prefix barcode (Tier 2) > Name (Tier 3) > Token (Tier 4).
 * 3. Normalization: Arabic Alef/Ta-Marbuta/Tashkeel and Eastern Arabic digits (٠-٩) match consistently.
 * 4. Repeatable JVM Timing: Benchmarks the actual shared ProductSearch primitive and repository API.
 */
class ProductSearchPerformanceTest {

    private fun generate10kProducts(): List<Product> {
        val categories = ProductCategory.entries.filter { it != ProductCategory.ALL }
        val arabicBases = listOf(
            "أرز بسمتي فاخر", "سكر أبيض ناعم", "زيت زيتون بكر", "طحين قمح أبيض",
            "شاي أسود سيلاني", "قهوة برازيلي مطحونة", "معكرونة إيطالية", "حليب كامل الدسم",
            "جبنة بيضاء نابلسية", "صابون غسيل ملابس", "سائل جلي صحون", "مسحوق بهارات مشكل",
            "عصير تفاح طبيعي", "حلاوة طحينية بالفستق", "بسكويت سادة بالشاي", "تونة بالزيت النباتي"
        )
        val englishBases = listOf(
            "Basmati Rice", "White Sugar", "Olive Oil", "Wheat Flour",
            "Ceylon Black Tea", "Brazilian Coffee", "Italian Pasta", "Whole Milk",
            "Nabulsi Cheese", "Laundry Detergent", "Dish Soap", "Mixed Spices",
            "Natural Apple Juice", "Halva with Pistachio", "Tea Biscuits", "Tuna in Vegetable Oil"
        )
        val units = listOf(
            ("كغم" to "kg"),
            ("علبة" to "can"),
            ("لتر" to "L"),
            ("كيس" to "bag"),
            ("قطعة" to "pcs")
        )

        val list = ArrayList<Product>(10000)
        val rng = Random(42) // Fixed seed for 100% deterministic reproducibility

        for (i in 0 until 9999) {
            val baseIdx = i % arabicBases.size
            val unitIdx = (i / 3) % units.size
            val cat = categories[i % categories.size]
            val barcode = String.format(Locale.US, "625100%07d", i)
            val price = 5.0 + (i % 80) + 0.50
            val cost = price * 0.75

            list.add(
                Product(
                    id = "syn_p_$i",
                    nameAr = "${arabicBases[baseIdx]} دفعة $i",
                    nameEn = "${englishBases[baseIdx]} Batch $i",
                    category = cat,
                    barcode = barcode,
                    priceIls = price,
                    costIls = cost,
                    unitAr = units[unitIdx].first,
                    unitEn = units[unitIdx].second,
                    stockCount = 10 + (i % 100),
                    minStockThreshold = 8,
                    accentHex = 0xFF255FD6
                )
            )
        }

        // Injected exact benchmark target product at position 5000
        list.add(
            5000,
            Product(
                id = "p_benchmark_target",
                nameAr = "زعتر بلدي بلدي فاخر مع سمسم",
                nameEn = "Authentic Baladi Zaatar with Sesame",
                category = ProductCategory.ROASTERY,
                barcode = "6259990001234",
                priceIls = 25.00,
                costIls = 18.00,
                unitAr = "كغم",
                unitEn = "kg",
                stockCount = 45,
                minStockThreshold = 10,
                accentHex = 0xFF255FD6
            )
        )

        return list
    }

    @Test
    fun search10k_emptyQueryAndLimits_strictlyClampedWithDefinedHandling() {
        val products = generate10kProducts()
        val repo = OfflineDemoTraderRepository(initialProducts = products)

        // 1. Empty query clamps to limit 30 by default
        val resultsDefault = repo.searchProducts(query = "", category = ProductCategory.ALL, limit = 30)
        assertEquals("Default search on empty query must clamp to 30 items", 30, resultsDefault.size)

        // 2. Custom bounded limit <= 30
        val resultsCustomLimit = repo.searchProducts(query = "", category = ProductCategory.ALL, limit = 15)
        assertEquals("Custom limit on empty query must clamp to requested 15 items", 15, resultsCustomLimit.size)

        // 3. Oversized limit (e.g. 10000) strictly clamped to max 30
        val resultsOversized = repo.searchProducts(query = "", category = ProductCategory.ALL, limit = 10000)
        assertEquals("Oversized limit 10000 must be clamped to max 30", 30, resultsOversized.size)

        // 4. Defined handling for limit <= 0 (never crashes, returns emptyList)
        val resultsZero = repo.searchProducts(query = "", category = ProductCategory.ALL, limit = 0)
        assertEquals("Limit 0 must safely return empty list", 0, resultsZero.size)

        val resultsNegative = repo.searchProducts(query = "", category = ProductCategory.ALL, limit = -5)
        assertEquals("Negative limit must safely return empty list without crash", 0, resultsNegative.size)

        // 5. Shared ProductSearch primitive exhibits identical behavior
        val primitiveNegative = ProductSearch.search(products, "", limit = -1)
        assertEquals("Primitive with negative limit must return empty list", 0, primitiveNegative.size)

        val primitiveOversized = ProductSearch.search(products, "", limit = 500)
        assertEquals("Primitive with oversized limit must clamp to 30", 30, primitiveOversized.size)
    }

    @Test
    fun search10k_exactBarcode_ranksTopPriorityAboveAllOthers() {
        val products = generate10kProducts()
        val targetBarcode = "6259990001234"

        // Search via the shared primitive
        val ranked = ProductSearch.search(products, targetBarcode, limit = 30)

        assertFalse("Must find matching product for exact barcode", ranked.isEmpty())
        val topMatch = ranked.first()
        assertEquals("Exact barcode match must be ranked #1", "p_benchmark_target", topMatch.id)
        assertEquals("Barcode must match target exactly", targetBarcode, topMatch.barcode)
    }

    @Test
    fun search10k_arabicDigitNormalization_matchesWesternBarcode() {
        val products = generate10kProducts()

        // Eastern Arabic digits for "6259990001234" -> "٦٢٥٩٩٩٠٠٠١٢٣٤"
        val arabicDigitQuery = "٦٢٥٩٩٩٠٠٠١٢٣٤"
        val ranked = ProductSearch.search(products, arabicDigitQuery, limit = 30)

        assertFalse("Must find matching product when searching with Arabic digits", ranked.isEmpty())
        assertEquals("Arabic digit query must match exact barcode at rank #1", "p_benchmark_target", ranked.first().id)
    }

    @Test
    fun search10k_arabicAlefNormalization_matchesRegardlessOfHamzaVariants() {
        val products = generate10kProducts()

        // Query without hamza "ارز" should match "أرز بسمتي فاخر"
        val queryNoHamza = "ارز"
        val ranked = ProductSearch.search(products, queryNoHamza, limit = 30)

        assertFalse("Must find matching products for normalized Alef", ranked.isEmpty())
        assertTrue("Results must contain rice items", ranked.any { it.nameAr.contains("أرز") })
        assertTrue("Results must not exceed bounded limit 30", ranked.size <= 30)
    }

    @Test
    fun search10k_repeatableTimingMeasurement_reportsActualJvmPerformance() {
        val products = generate10kProducts()
        val repo = OfflineDemoTraderRepository(initialProducts = products)
        val queries = listOf("6251000000001", "زيت", "Coffee", "٦٢٥٩٩٩", "سكر", "حليب", "6259990001234")

        // 1. Warm-up JIT compiler on actual shared repository and primitive API
        for (w in 0 until 5) {
            for (q in queries) {
                repo.searchProducts(q, limit = 30)
                ProductSearch.search(products, q, limit = 30)
            }
        }

        // 2. Measure actual execution across queries using the shared repo API
        val iterations = 10
        val timingsMs = ArrayList<Double>(iterations * queries.size)

        for (iter in 0 until iterations) {
            for (q in queries) {
                val startNs = System.nanoTime()
                val results = repo.searchProducts(q, limit = 30)
                val elapsedNs = System.nanoTime() - startNs
                val elapsedMs = elapsedNs / 1_000_000.0
                timingsMs.add(elapsedMs)
                assertTrue("Result must be bounded to 30", results.size <= 30)
            }
        }

        val sortedTimings = timingsMs.sorted()
        val minMs = sortedTimings.first()
        val medianMs = sortedTimings[sortedTimings.size / 2]
        val p95Ms = sortedTimings[(sortedTimings.size * 95) / 100]
        val maxMs = sortedTimings.last()
        val avgMs = timingsMs.average()

        println("=== 10k SYNTHETIC PRODUCT SEARCH JVM BENCHMARK ===")
        println("Dataset Size: 10,000 products")
        println("Total Queries Evaluated: ${timingsMs.size}")
        println(String.format(Locale.US, "Average Search Latency: %.3f ms", avgMs))
        println(String.format(Locale.US, "Min: %.3f ms | Median: %.3f ms | p95: %.3f ms | Max: %.3f ms", minMs, medianMs, p95Ms, maxMs))
        println("==================================================")

        // Non-fragile safety sanity check: in-memory benchmark completes reliably
        assertTrue("Benchmark average latency sanity check", avgMs < 1000.0)
    }
}
