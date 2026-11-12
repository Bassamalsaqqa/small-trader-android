package com.example.data.mobile.session

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid

/**
 * Explicit scope parameters that must be verified against the authenticated session
 * before exposing an active Bearer token.
 *
 * Contextual metadata is encrypted and authenticated alongside the token.
 * Cross-scope fallback is strictly prohibited.
 */
data class SessionReadScope(
    val expectedDeviceUuid: ClientUuid,
    val expectedActorUlid: ServerUlid? = null,
    val expectedCompanyId: ServerUlid? = null
)
