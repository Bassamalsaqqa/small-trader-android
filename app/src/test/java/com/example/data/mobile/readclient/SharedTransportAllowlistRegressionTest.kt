package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression tests for shared transport endpoint allowlist and method verification.
 * Verifies that Mobile V1 read endpoints and Mobile V2 online cash endpoints
 * are strictly allowlisted while retired or unauthorized paths are rejected.
 */
class SharedTransportAllowlistRegressionTest {

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")

    @Test
    fun testV1AuthAndReadEndpointsAllowlisted() {
        // V1 Auth paths
        EndpointSecurity.validatePath("/api/mobile/v1/auth/challenge")
        EndpointSecurity.validatePath("/api/mobile/v1/auth/login")
        EndpointSecurity.validatePath("/api/mobile/v1/auth/logout")

        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/challenge")
        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/login")
        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/logout")

        // V1 Tenant read paths
        val bootstrapPath = EndpointSecurity.bootstrapPath(companyId)
        val productsPath = EndpointSecurity.productsPath(companyId)
        val customersPath = EndpointSecurity.customersPath(companyId)

        EndpointSecurity.validatePath(bootstrapPath)
        EndpointSecurity.validatePath(productsPath)
        EndpointSecurity.validatePath(customersPath)

        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, bootstrapPath)
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, productsPath)
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, customersPath)
    }

    @Test
    fun testV2OnlineCashAndStatusEndpointsAllowlisted() {
        val onlineCashPath = EndpointSecurity.onlineCashSalePath(companyId)
        val statusPath = EndpointSecurity.offlineEventStatusPath(companyId, clientEventId)

        EndpointSecurity.validatePath(onlineCashPath)
        EndpointSecurity.validatePath(statusPath)

        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, onlineCashPath)
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, statusPath)
    }

    @Test
    fun testDisallowedMethodsOnV1AndV2EndpointsRejected() {
        // GET on POST-only endpoints
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/auth/challenge")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/auth/login")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/auth/logout")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, EndpointSecurity.onlineCashSalePath(companyId))
        }

        // POST on GET-only endpoints
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.POST, EndpointSecurity.bootstrapPath(companyId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.POST, EndpointSecurity.productsPath(companyId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.POST, EndpointSecurity.customersPath(companyId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.POST, EndpointSecurity.offlineEventStatusPath(companyId, clientEventId))
        }
    }

    @Test
    fun testUnauthorizedAndRetiredPathsRejected() {
        // Arbitrary / retired paths
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v1/devices/${clientEventId.value}/revoke")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v1/companies/${companyId.value}/sales/commit")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v2/companies/${companyId.value}/sales/offline-intake")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/v1/arbitrary")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v2/companies/INVALID_ULID/sales/online-cash")
        }
    }
}
