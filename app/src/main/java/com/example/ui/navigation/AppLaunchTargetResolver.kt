package com.example.ui.navigation

import com.example.data.mobile.readclient.EndpointSecurity

/**
 * Launch target selection policy governing the entry point of the application.
 *
 * Enforces AGENTS.md § 1 (No Live Demo Fallback):
 * - Production and staging builds must never silently fall back to OfflineDemoTraderRepository,
 *   sample catalogs, or simulated staff/cash states.
 * - Demo mode (SmallTraderApp) is strictly segregated and only permitted in debug builds with
 *   an explicit demo opt-in flag.
 * - When the backend origin is unconfigured or blank, production and staging builds route
 *   truthfully to UNCONFIGURED (displaying StaffUnconfiguredScreen), never to demo/sample catalogs.
 * - Backwards compatibility is preserved for existing explicit pilot configuration:
 *   smalltrader.staffReadWorkspace / STAFF_READ_WORKSPACE.
 * - Cash-free invariant: Contains zero online cash domain/client references.
 */
enum class AppLaunchTarget {
    STAFF_WORKSPACE,
    DEMO_TRADER,
    UNCONFIGURED
}

object AppLaunchTargetResolver {

    /**
     * Resolves the application launch target based on build variant, explicit pilot flags,
     * configured backend origin, and demo mode opt-in status.
     *
     * @param isDebug Whether running under a debug build variant (e.g. BuildConfig.DEBUG).
     * @param staffReadWorkspace Back-compat flag for explicit staff workspace pilot (BuildConfig.STAFF_READ_WORKSPACE).
     * @param configuredOrigin Base URL/origin of the backend server (BuildConfig.STAFF_WORKSPACE_ORIGIN).
     * @param demoModeOptIn Explicit opt-in flag for demo mode (e.g. BuildConfig.DEMO_MODE_ENABLED).
     * @param allowHttpLoopback Whether cleartext HTTP loopback is permitted (debug-only).
     * @return The resolved [AppLaunchTarget].
     */
    fun resolve(
        isDebug: Boolean,
        staffReadWorkspace: Boolean,
        configuredOrigin: String,
        demoModeOptIn: Boolean = false,
        allowHttpLoopback: Boolean = false
    ): AppLaunchTarget {
        val trimmedOrigin = configuredOrigin.trim()
        val hasValidOrigin = trimmedOrigin.isNotBlank() && isValidOrigin(
            origin = trimmedOrigin,
            allowHttpLoopback = isDebug && allowHttpLoopback
        )

        // 1. Explicit staff workspace pilot flag or valid origin configured:
        // If staffReadWorkspace is set OR a valid origin is provided, this app instance is designated for Staff.
        if (staffReadWorkspace || hasValidOrigin) {
            return if (hasValidOrigin) {
                AppLaunchTarget.STAFF_WORKSPACE
            } else {
                // If staffReadWorkspace was explicitly requested but origin is blank or invalid,
                // fail closed to UNCONFIGURED. Never fall back silently to demo.
                AppLaunchTarget.UNCONFIGURED
            }
        }

        // 2. Demo mode: STRICTLY gated to debug builds with explicit demo opt-in.
        // Production, staging, and release builds NEVER allow demo mode.
        if (isDebug && demoModeOptIn) {
            return AppLaunchTarget.DEMO_TRADER
        }

        // 3. Fallback for unconfigured origin (in release, staging, or debug without explicit demo opt-in):
        // Show truthful UNCONFIGURED setup screen. Never fall back to sample catalog.
        return AppLaunchTarget.UNCONFIGURED
    }

    /**
     * Checks if the given origin is a valid base origin conforming to platform security rules:
     * - Absolute URI with scheme and non-empty host.
     * - HTTPS required by default; HTTP only permitted if loopback and allowHttpLoopback is true.
     * - No credentials, path segments, query parameters, or fragments.
     */
    fun isValidOrigin(origin: String, allowHttpLoopback: Boolean): Boolean {
        if (origin.isBlank()) return false
        return try {
            EndpointSecurity.validateBaseOrigin(origin, allowHttpLoopback)
            true
        } catch (_: Exception) {
            false
        }
    }
}
