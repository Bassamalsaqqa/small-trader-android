package com.example.data

import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import java.math.BigDecimal
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DodyReviewRepositoryContractTest {

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val customerId = "01JCV8X9M2NQW3TYZ4R5S6789C"
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"

    private class QueueTransport : ReadClientTransport {
        val responseQueue = mutableListOf<TransportResponse>()

        override fun execute(request: TransportRequest): TransportResponse {
            return if (responseQueue.isNotEmpty()) {
                responseQueue.removeAt(0)
            } else {
                TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not queued"}}""")
            }
        }
    }

    private fun jsonOk(body: String) = TransportResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = body.trimIndent()
    )

    private fun createRepository(transport: ReadClientTransport): ProductionTraderRepository {
        val tokenProvider = MerchantReadTokenProvider { reqComp, _ ->
            if (reqComp == companyId) validToken else null
        }
        val readClient = MerchantReadClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = tokenProvider
        )
        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )
        return ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = {
                CurrentAuthScope(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    token = validToken,
                    generation = 1L
                )
            },
            merchantReadClient = readClient
        )
    }

    @Test
    fun getCustomerDetail_explicitNullCreditLimit_clearsCachedLimit_andOmittedKeyRetainsCachedLimit() = runBlocking {
        val transport = QueueTransport()
        val repo = createRepository(transport)

        // 1. Seed directory cache + credit_limit = 750.00 via valid CustomerDirectoryResponse + initial detail load
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "company_id": "${companyId.value}",
                    "generated_at": "2026-11-28T10:00:00Z",
                    "next_cursor": null,
                    "has_more": false,
                    "customers": [
                        {
                            "id": "$customerId",
                            "code": "C-001",
                            "name": "زبون اختبار",
                            "phone": "0599000111",
                            "is_walk_in": false
                        }
                    ]
                }
                """
            )
        )
        val initialSync = repo.refreshCustomers()
        assertTrue("Initial directory refresh must succeed: ${initialSync.exceptionOrNull()}", initialSync.isSuccess)

        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار",
                    "balance": "120.00",
                    "credit_limit": "750.00"
                }
                """
            )
        )
        val initialDetail = repo.getCustomerDetail(customerId)
        assertTrue("Initial detail load must succeed: ${initialDetail.exceptionOrNull()}", initialDetail.isSuccess)
        assertEquals(BigDecimal("750.00"), repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)

        // 2. Explicit credit_limit: null MUST clear both returned detail and cached customer creditLimitExact
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار",
                    "balance": "120.00",
                    "credit_limit": null
                }
                """
            )
        )
        val clearedRes = repo.getCustomerDetail(customerId)
        assertTrue(clearedRes.isSuccess)
        assertNull("Returned detail must clear creditLimitExact on explicit null", clearedRes.getOrNull()!!.creditLimitExact)
        assertNull("Cached customersFlow must clear creditLimitExact on explicit null", repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)

        // 3. Re-populate credit_limit = 500.00 via detail response
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار",
                    "balance": "120.00",
                    "credit_limit": "500.00"
                }
                """
            )
        )
        val setRes = repo.getCustomerDetail(customerId)
        assertTrue(setRes.isSuccess)
        assertEquals(BigDecimal("500.00"), setRes.getOrNull()!!.creditLimitExact)
        assertEquals(BigDecimal("500.00"), repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)

        // 4. Omitted credit_limit keys MUST retain cached creditLimitExact (500.00)
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار محدّث",
                    "balance": "90.00"
                }
                """
            )
        )
        val retainedRes = repo.getCustomerDetail(customerId)
        assertTrue(retainedRes.isSuccess)
        val retainedDetail = retainedRes.getOrNull()
        assertNotNull(retainedDetail)
        assertEquals(BigDecimal("500.00"), retainedDetail!!.creditLimitExact)
        assertEquals(BigDecimal("90.00"), retainedDetail.balanceExact)
        assertEquals(
            "Cached customersFlow must retain 500.00 when credit_limit is omitted",
            BigDecimal("500.00"),
            repo.customersFlow.value.first { it.id == customerId }.creditLimitExact
        )

        // 5. Explicit legacy credit_limit_exact: null also clears cached creditLimitExact
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار محدّث",
                    "balance": "90.00",
                    "credit_limit_exact": null
                }
                """
            )
        )
        val clearedExactKey = repo.getCustomerDetail(customerId)
        assertTrue(clearedExactKey.isSuccess)
        assertNull(clearedExactKey.getOrNull()!!.creditLimitExact)
        assertNull(repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)

        // 6. Re-set to 320.00 then clear via explicit legacy credit_limit_ils: null
        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار",
                    "balance": "90.00",
                    "credit_limit": "320.00"
                }
                """
            )
        )
        assertTrue(repo.getCustomerDetail(customerId).isSuccess)
        assertEquals(BigDecimal("320.00"), repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)

        transport.responseQueue.add(
            jsonOk(
                """
                {
                    "id": "$customerId",
                    "name": "زبون اختبار",
                    "balance": "90.00",
                    "credit_limit_ils": null
                }
                """
            )
        )
        val clearedIlsKey = repo.getCustomerDetail(customerId)
        assertTrue(clearedIlsKey.isSuccess)
        assertNull(clearedIlsKey.getOrNull()!!.creditLimitExact)
        assertNull(repo.customersFlow.value.first { it.id == customerId }.creditLimitExact)
    }
    @Test
    fun staleNullDetailCannotClearNewerSavedCreditLimit() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val gets = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method.name == "GET" && gets.incrementAndGet() > 1) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return jsonOk("""{"id":"$customerId","name":"Customer","balance":"90.00","credit_limit":null}""")
                }
                return jsonOk("""{"id":"$customerId","name":"Customer","balance":"90.00","credit_limit":"750.00"}""")
            }
        }
        val repo = createRepository(transport)
        try {
            assertTrue(repo.getCustomerDetail(customerId).isSuccess)
            val stale = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                repo.getCustomerDetail(customerId)
            }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(repo.updateCustomer(customerId, "Customer", null, null, BigDecimal("750.00"), false, false, true).isSuccess)
            } finally {
                release.countDown()
            }
            assertEquals(BigDecimal("750.00"), stale.await().getOrThrow().creditLimitExact)
            assertEquals(BigDecimal("750.00"), repo.customersFlow.value.single().creditLimitExact)
        } finally {
            release.countDown()
            repo.close()
        }
    }

}
