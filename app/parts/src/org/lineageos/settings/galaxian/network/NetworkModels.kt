/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

internal enum class WifiStatus { UNAVAILABLE, DISABLED, DISCONNECTED, CONNECTED }
internal enum class MobileServiceState { UNKNOWN, IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF }

internal data class WifiSnapshot(
    val status: WifiStatus = WifiStatus.UNAVAILABLE,
    val rssiDbm: Int? = null,
    val level: Int? = null,
    val frequencyMhz: Int? = null,
    val rxLinkMbps: Int? = null,
    val txLinkMbps: Int? = null,
    val standard: String? = null,
    val validated: Boolean = false,
)

internal data class CellularSignal(
    val technology: String,
    val dbm: Int? = null,
    val level: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
)

internal data class SimSnapshot(
    val slotIndex: Int,
    val isDefaultData: Boolean = false,
    val serviceState: MobileServiceState = MobileServiceState.UNKNOWN,
    val dataNetworkType: String? = null,
    // This is the modem's display classification; it is not proof of an active NR data bearer.
    val displayNetworkType: String? = null,
    val signals: List<CellularSignal> = emptyList(),
)

internal data class MobileSnapshot(
    val permissionGranted: Boolean = false,
    val available: Boolean = false,
    val sims: List<SimSnapshot> = emptyList(),
)

internal data class DefaultNetworkSnapshot(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val vpn: Boolean = false,
    val transports: List<String> = emptyList(),
)

internal data class NetworkSnapshot(
    val wifi: WifiSnapshot = WifiSnapshot(),
    val mobile: MobileSnapshot = MobileSnapshot(),
    val defaultNetwork: DefaultNetworkSnapshot = DefaultNetworkSnapshot(),
)

/** Reject unknown/out-of-range framework values instead of turning sentinels into measurements. */
internal object NetworkValues {
    fun wifiRssi(value: Int): Int? = value.takeIf { it in -126..-1 }
    fun signalDbm(value: Int): Int? = value.takeIf { it in -160..-1 }
    fun level(value: Int): Int? = value.takeIf { it in 0..4 }
    fun positive(value: Int): Int? = value.takeIf { it in 1 until Int.MAX_VALUE }
    fun metric(value: Int, range: IntRange): Int? = value.takeIf { it in range }
}

/** Queued signal/display callbacks must not bring back readings after the radio was turned off. */
internal fun SimSnapshot.withRadioTelemetry(update: (SimSnapshot) -> SimSnapshot): SimSnapshot =
    if (serviceState == MobileServiceState.POWER_OFF) this else update(this)
