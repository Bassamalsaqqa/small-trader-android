package com.example.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy unit tests for [AppLaunchTargetResolver] enforcing AGENTS.md § 1 NoLiveDemoFallback:
 *
 * 1. Debug explicit demo allowed:
 *    - In debug builds with explicit demo opt-in and unconfigured staff origin -> DEMO_TRADER.
 * 2. Release / staging demo refused:
 *    - In non-debug builds (release, benchmark, staging), demo opt-in is strictly refused -> UNCONFIGURED.
 * 3. Blank origin no demo fallback:
 *    - Blank origin never falls back to demo/sample catalog in release/staging or in debug without opt-in.
 * 4. Valid origin -> Staff workspace:
 *    - Valid HTTPS origin routes directly to STAFF_WORKSPACE regardless of variant.
 * 5. Backwards-compatibility for existing explicit pilot config:
 *    - staffReadWorkspace = true with valid origin -> STAFF_WORKSPACE.
 *    - staffReadWorkspace = true with blank origin -> UNCONFIGURED (no silent demo fallback).
 * 6. Loopback cleartext HTTP security:
 *    - Only allowed when isDebug == true AND allowHttpLoopback == true AND host is loopback.
 */
class AppLaunchTargetResolverTest {

    @Test
    fun testDebugExplicitDemoAllowed() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "",
            demoModeOptIn = true,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.DEMO_TRADER, target)
    }

    @Test
    fun testReleaseDemoRefusedEvenWithOptIn() {
        // Release build must NEVER fall back to demo, even if demo opt-in flag is true
        val target = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "",
            demoModeOptIn = true,
            allowHttpLoopback = false
        )
        assertEquals(
            "Release builds must refuse demo mode and route to UNCONFIGURED",
            AppLaunchTarget.UNCONFIGURED,
            target
        )
    }

    @Test
    fun testNonDebugPolicyDemoRefusedEvenWithOptIn() {
        // Non-debug builds (release, benchmark, non-debug policy) strictly refuse demo fallback
        val target = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "",
            demoModeOptIn = true,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, target)
    }

    @Test
    fun testBlankOriginRefusesDemoInRelease() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(
            "Release build without configured origin must route to UNCONFIGURED",
            AppLaunchTarget.UNCONFIGURED,
            target
        )
    }

    @Test
    fun testBlankOriginRefusesDemoInDebugWithoutOptIn() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(
            "Debug build without demo opt-in must route to UNCONFIGURED",
            AppLaunchTarget.UNCONFIGURED,
            target
        )
    }

    @Test
    fun testValidOriginRoutesToStaffWorkspaceInRelease() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "https://api.smalltrader.example",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, target)
    }

    @Test
    fun testValidOriginRoutesToStaffWorkspaceInDebug() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "https://api.smalltrader.example",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, target)
    }

    @Test
    fun testValidOriginTakesPrecedenceOverDemoOptInInDebug() {
        // When a valid staff origin is configured, it takes precedence over demo opt-in
        val target = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "https://api.smalltrader.example",
            demoModeOptIn = true,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, target)
    }

    @Test
    fun testExistingStaffReadWorkspacePilotConfigBackcompatWithValidOrigin() {
        val target = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = true,
            configuredOrigin = "https://api.smalltrader.example",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, target)
    }

    @Test
    fun testExistingStaffReadWorkspacePilotConfigBackcompatWithBlankOriginRefusesDemo() {
        // staffReadWorkspace = true with empty origin must refuse demo in both release and debug
        val targetRelease = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = true,
            configuredOrigin = "",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetRelease)

        val targetDebug = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = true,
            configuredOrigin = "",
            demoModeOptIn = true, // even if demo opt-in is present, staff pilot explicitly requests staff workspace
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetDebug)
    }

    @Test
    fun testHttpLoopbackAllowedInDebugOnlyWhenExplicitlyFlagged() {
        // Debug + loopback flag + localhost -> STAFF_WORKSPACE
        val targetLocalhost = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "http://localhost:8000",
            demoModeOptIn = false,
            allowHttpLoopback = true
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, targetLocalhost)

        // Debug + loopback flag + 127.0.0.1 -> STAFF_WORKSPACE
        val targetIpv4 = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "http://127.0.0.1:8000",
            demoModeOptIn = false,
            allowHttpLoopback = true
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, targetIpv4)

        // Debug + loopback flag + Android emulator host 10.0.2.2 -> STAFF_WORKSPACE
        val targetEmulatorHost = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "http://10.0.2.2:8000",
            demoModeOptIn = false,
            allowHttpLoopback = true
        )
        assertEquals(AppLaunchTarget.STAFF_WORKSPACE, targetEmulatorHost)

        // Debug + allowHttpLoopback FALSE + loopback origin -> UNCONFIGURED
        val targetDisallowed = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "http://127.0.0.1:8000",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetDisallowed)

        // Release + allowHttpLoopback TRUE (attempted override) + loopback origin -> UNCONFIGURED (release refuses cleartext)
        val targetReleaseDisallowed = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "http://127.0.0.1:8000",
            demoModeOptIn = false,
            allowHttpLoopback = true
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetReleaseDisallowed)
    }

    @Test
    fun testNonLoopbackHttpRefusedInAllVariants() {
        // Non-loopback HTTP is strictly prohibited even if allowHttpLoopback is true
        val targetDebug = AppLaunchTargetResolver.resolve(
            isDebug = true,
            staffReadWorkspace = false,
            configuredOrigin = "http://insecure.smalltrader.example",
            demoModeOptIn = false,
            allowHttpLoopback = true
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetDebug)

        val targetRelease = AppLaunchTargetResolver.resolve(
            isDebug = false,
            staffReadWorkspace = false,
            configuredOrigin = "http://insecure.smalltrader.example",
            demoModeOptIn = false,
            allowHttpLoopback = false
        )
        assertEquals(AppLaunchTarget.UNCONFIGURED, targetRelease)
    }

    @Test
    fun testMalformedAndInsecureOriginsRefused() {
        val invalidOrigins = listOf(
            "ftp://api.smalltrader.example",
            "https://user:pass@api.smalltrader.example",
            "https://api.smalltrader.example/subpath",
            "https://api.smalltrader.example?query=param",
            "https://api.smalltrader.example#fragment",
            "not_a_valid_url",
            "://missing-scheme",
            "https://",
            "   "
        )

        for (origin in invalidOrigins) {
            val target = AppLaunchTargetResolver.resolve(
                isDebug = false,
                staffReadWorkspace = false,
                configuredOrigin = origin,
                demoModeOptIn = false,
                allowHttpLoopback = false
            )
            assertEquals("Origin '$origin' must resolve to UNCONFIGURED", AppLaunchTarget.UNCONFIGURED, target)
        }
    }

    @Test
    fun testIsValidOriginDirectContract() {
        assertTrue(AppLaunchTargetResolver.isValidOrigin("https://api.smalltrader.example", allowHttpLoopback = false))
        assertTrue(AppLaunchTargetResolver.isValidOrigin("https://api.smalltrader.example:8443", allowHttpLoopback = false))
        assertFalse(AppLaunchTargetResolver.isValidOrigin("", allowHttpLoopback = false))
        assertFalse(AppLaunchTargetResolver.isValidOrigin("   ", allowHttpLoopback = false))
        assertFalse(AppLaunchTargetResolver.isValidOrigin("http://127.0.0.1:8000", allowHttpLoopback = false))
        assertTrue(AppLaunchTargetResolver.isValidOrigin("http://127.0.0.1:8000", allowHttpLoopback = true))
        assertFalse(AppLaunchTargetResolver.isValidOrigin("http://example.com", allowHttpLoopback = true))
        assertFalse(AppLaunchTargetResolver.isValidOrigin("https://api.example.com/path", allowHttpLoopback = true))
    }
}
