/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.io.Closeable

/** Screen-owned observation only. No network probes, Wi-Fi scans or subscriber identifiers. */
internal class NetworkRepository(context: Context, private val onSnapshot: (NetworkSnapshot) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = this.context.getSystemService(WifiManager::class.java)
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private var generation = 0
    private var active = false
    private var snapshot = NetworkSnapshot()
    private val wifiNetworks = linkedMapOf<Network, WifiSnapshot>()
    private var defaultNetwork: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var dataSubscriptionListener: TelephonyCallback? = null
    private data class SimRegistration(val manager: TelephonyManager, val callback: TelephonyCallback)
    private val simRegistrations = mutableMapOf<Int, SimRegistration>()
    private val simStates = mutableMapOf<Int, SimSnapshot>()

    fun start(mobilePermission: Boolean) = onMain {
        stop()
        active = true
        val token = generation
        val granted = mobilePermission && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        snapshot = NetworkSnapshot(mobile = MobileSnapshot(permissionGranted = granted))
        startWifi(token)
        startDefaultNetwork(token)
        if (granted) startMobile(token)
        publish()
    }

    override fun close() = onMain { stop() }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post(action)
    }

    private fun current(token: Int) = active && generation == token
    private fun publish() { if (active) onSnapshot(snapshot) }

    private fun stop() {
        active = false
        generation++
        wifiCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        defaultCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        wifiReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        subscriptionListener?.let { runCatching { subscriptions?.removeOnSubscriptionsChangedListener(it) } }
        dataSubscriptionListener?.let { runCatching { telephony?.unregisterTelephonyCallback(it) } }
        simRegistrations.values.forEach { runCatching { it.manager.unregisterTelephonyCallback(it.callback) } }
        wifiCallback = null
        defaultCallback = null
        wifiReceiver = null
        subscriptionListener = null
        dataSubscriptionListener = null
        simRegistrations.clear()
        simStates.clear()
        wifiNetworks.clear()
        defaultNetwork = null
    }

    private fun startWifi(token: Int) {
        if (wifiManager == null || connectivity == null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!current(token)) return
                val info = capabilities.transportInfo as? WifiInfo
                wifiNetworks[network] = if (info == null) WifiSnapshot(status = WifiStatus.CONNECTED) else wifiReading(info).copy(
                    validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                updateWifi()
            }
            override fun onLost(network: Network) {
                if (!current(token)) return
                wifiNetworks.remove(network)
                updateWifi()
            }
        }
        // No FLAG_INCLUDE_LOCATION_INFO: SSID/BSSID and device addresses remain redacted.
        if (runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main) }.isSuccess) {
            wifiCallback = callback
        } else return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (current(token)) updateWifi()
            }
        }
        if (runCatching { context.registerReceiver(receiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), null, main,
                Context.RECEIVER_NOT_EXPORTED) }.isSuccess) wifiReceiver = receiver
        updateWifi()
    }

    private fun updateWifi() {
        val enabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull()
        if (enabled == false) wifiNetworks.clear()
        val reading = when (enabled) {
            false -> WifiSnapshot(status = WifiStatus.DISABLED)
            null -> WifiSnapshot()
            true -> wifiNetworks[defaultNetwork] ?: wifiNetworks.values.firstOrNull { it.validated }
                ?: wifiNetworks.values.firstOrNull() ?: WifiSnapshot(status = WifiStatus.DISCONNECTED)
        }
        snapshot = snapshot.copy(wifi = reading)
        publish()
    }

    private fun startDefaultNetwork(token: Int) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!current(token)) return
                defaultNetwork = network
                snapshot = snapshot.copy(defaultNetwork = DefaultNetworkSnapshot(connected = true))
                updateWifi()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!current(token)) return
                defaultNetwork = network
                snapshot = snapshot.copy(defaultNetwork = DefaultNetworkSnapshot(
                    connected = true, validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    transports = listOf(NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                        NetworkCapabilities.TRANSPORT_CELLULAR to "Cellular",
                        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
                        NetworkCapabilities.TRANSPORT_VPN to "VPN",
                        NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth")
                        .filter { caps.hasTransport(it.first) }.map { it.second }))
                updateWifi()
            }
            override fun onLost(network: Network) {
                if (!current(token) || network != defaultNetwork) return
                defaultNetwork = null
                snapshot = snapshot.copy(defaultNetwork = DefaultNetworkSnapshot())
                updateWifi()
            }
        }
        if (connectivity != null && runCatching { connectivity.registerDefaultNetworkCallback(callback, main) }.isSuccess) {
            defaultCallback = callback
        }
    }

    private fun startMobile(token: Int) {
        if (subscriptions == null || telephony == null ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() { if (current(token)) refreshSubscriptions(token) }
        }
        if (runCatching { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }.isSuccess) {
            subscriptionListener = listener
        }
        val dataListener = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                if (current(token)) refreshSubscriptions(token)
            }
        }
        if (runCatching { telephony.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                context.mainExecutor, dataListener) }.isSuccess) {
            dataSubscriptionListener = dataListener
        }
        refreshSubscriptions(token)
    }

    private fun refreshSubscriptions(token: Int) {
        val infos = runCatching { subscriptions?.activeSubscriptionInfoList }.getOrNull()
        if (infos == null) {
            simRegistrations.values.forEach { runCatching { it.manager.unregisterTelephonyCallback(it.callback) } }
            simRegistrations.clear()
            simStates.clear()
            snapshot = snapshot.copy(mobile = MobileSnapshot(permissionGranted =
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED))
            publish()
            return
        }
        val visible = infos.filter { it.simSlotIndex >= 0 }
        val ids = visible.map { it.subscriptionId }.toSet()
        ((simRegistrations.keys + simStates.keys) - ids).forEach { id ->
            simRegistrations.remove(id)?.let { runCatching { it.manager.unregisterTelephonyCallback(it.callback) } }
            simStates.remove(id)
        }
        val defaultId = SubscriptionManager.getDefaultDataSubscriptionId()
        visible.forEach { info ->
            val id = info.subscriptionId
            simStates[id] = (simStates[id] ?: SimSnapshot(info.simSlotIndex)).copy(
                slotIndex = info.simSlotIndex, isDefaultData = id == defaultId)
            if (id !in simRegistrations) {
                val manager = telephony?.createForSubscriptionId(id) ?: return@forEach
                val callback = SimCallback(token, id)
                if (runCatching { manager.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                        context.mainExecutor, callback) }.isSuccess) {
                    simRegistrations[id] = SimRegistration(manager, callback)
                    runCatching { manager.signalStrength }.getOrNull()?.let { callback.onSignalStrengthsChanged(it) }
                }
            }
        }
        publishMobile()
    }

    private fun publishMobile() {
        snapshot = snapshot.copy(mobile = MobileSnapshot(permissionGranted = true, available = true,
            sims = simStates.values.sortedBy { it.slotIndex }))
        publish()
    }

    private inner class SimCallback(private val token: Int, private val id: Int) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DataConnectionStateListener, TelephonyCallback.DisplayInfoListener {
        private fun update(serviceStateChange: Boolean = false, transform: (SimSnapshot) -> SimSnapshot) {
            if (!current(token) || simRegistrations[id]?.callback !== this) return
            val before = simStates[id] ?: return
            simStates[id] = if (serviceStateChange) transform(before) else before.withRadioTelemetry(transform)
            publishMobile()
        }
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = update {
            it.copy(signals = signalReadings(signalStrength))
        }
        override fun onServiceStateChanged(serviceState: ServiceState) = update(serviceStateChange = true) {
            it.withServiceState(serviceState)
        }
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) = update {
            it.copy(dataNetworkType = networkTypeLabel(networkType))
        }
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = update {
            it.copy(displayNetworkType = when (info.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE Advanced Pro"
                else -> networkTypeLabel(info.networkType)
            })
        }
    }
}

private fun wifiReading(info: WifiInfo): WifiSnapshot {
    val rssi = NetworkValues.wifiRssi(info.rssi)
    return WifiSnapshot(status = WifiStatus.CONNECTED, rssiDbm = rssi,
        level = rssi?.let { WifiManager.calculateSignalLevel(it, 5) },
        frequencyMhz = NetworkValues.positive(info.frequency),
        rxLinkMbps = NetworkValues.positive(info.rxLinkSpeedMbps),
        txLinkMbps = NetworkValues.positive(info.txLinkSpeedMbps),
        standard = when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
            else -> null
        })
}

internal fun signalReadings(strength: SignalStrength): List<CellularSignal> =
    strength.cellSignalStrengths.mapNotNull(::cellularSignalReading)

internal fun cellularSignalReading(cell: CellSignalStrength): CellularSignal? {
    val technology = when (cell) {
        is CellSignalStrengthLte -> "LTE"
        is CellSignalStrengthNr -> "NR"
        is CellSignalStrengthGsm -> "GSM"
        is CellSignalStrengthWcdma -> "WCDMA"
        is CellSignalStrengthTdscdma -> "TD-SCDMA"
        is CellSignalStrengthCdma -> "CDMA"
        else -> return null
    }
    val dbm = NetworkValues.signalDbm(cell.dbm)
    val rsrp = when (cell) {
        is CellSignalStrengthLte -> NetworkValues.metric(cell.rsrp, -140..-43)
        is CellSignalStrengthNr -> NetworkValues.metric(cell.ssRsrp, -156..-31)
        else -> null
    }
    val rsrq = when (cell) {
        is CellSignalStrengthLte -> NetworkValues.metric(cell.rsrq, -34..3)
        is CellSignalStrengthNr -> NetworkValues.metric(cell.ssRsrq, -43..20)
        else -> null
    }
    val sinr = when (cell) {
        is CellSignalStrengthLte -> NetworkValues.metric(cell.rssnr, -20..30)
        is CellSignalStrengthNr -> NetworkValues.metric(cell.ssSinr, -23..40)
        else -> null
    }
    return if (dbm == null && rsrp == null && rsrq == null && sinr == null) null
    else CellularSignal(technology, dbm, NetworkValues.level(cell.level), rsrp, rsrq, sinr)
}

internal fun networkTypeLabel(type: Int): String? = when (type) {
    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
    TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
    TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
    TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
    TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
    TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
    TelephonyManager.NETWORK_TYPE_NR -> "NR"
    else -> null
}

internal fun SimSnapshot.withServiceState(state: ServiceState): SimSnapshot {
    val value = when {
        state.state == ServiceState.STATE_IN_SERVICE ||
            state.dataRegistrationState == ServiceState.STATE_IN_SERVICE -> MobileServiceState.IN_SERVICE
        state.state == ServiceState.STATE_POWER_OFF -> MobileServiceState.POWER_OFF
        state.isEmergencyOnly -> MobileServiceState.EMERGENCY_ONLY
        state.state == ServiceState.STATE_OUT_OF_SERVICE -> MobileServiceState.OUT_OF_SERVICE
        else -> MobileServiceState.UNKNOWN
    }
    return if (value == MobileServiceState.POWER_OFF) copy(serviceState = value,
        dataNetworkType = null, displayNetworkType = null, signals = emptyList())
    else copy(serviceState = value)
}
