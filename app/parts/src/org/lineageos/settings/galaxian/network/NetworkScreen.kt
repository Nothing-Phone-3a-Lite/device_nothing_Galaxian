/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold
import org.lineageos.settings.galaxian.ui.rememberPartsResumed

private data class TcpConfirmation(val expected: TcpState, val target: String, val restore: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumed = rememberPartsResumed()
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var phonePermission by remember { mutableStateOf(hasPhonePermission(context)) }
    var network by remember { mutableStateOf(NetworkSnapshot()) }
    var tcp by remember { mutableStateOf<TcpState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TcpResult?>(null) }
    var operationFailed by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<TcpConfirmation?>(null) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    val wifiScroll = rememberLazyListState()
    val simScroll = rememberLazyListState()
    val tcpScroll = rememberLazyListState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phonePermission = hasPhonePermission(context)
    }

    LaunchedEffect(resumed) {
        if (resumed) phonePermission = hasPhonePermission(context)
        else confirmation = null
    }
    DisposableEffect(resumed, phonePermission) {
        val repository = if (resumed) NetworkRepository(context) { network = it }.also {
            it.start(phonePermission)
        } else null
        onDispose { repository?.close() }
    }
    LaunchedEffect(resumed, selectedTab) {
        confirmation = null
        if (!resumed || selectedTab != 2) return@LaunchedEffect
        while (isActive) {
            if (!busy) tcp = withContext(Dispatchers.IO) { TcpControlSession.manager.readState() }
            delay(1_000)
        }
    }

    fun openSettings(vararg actions: String) {
        settingsUnavailable = !actions.any { action ->
            runCatching { context.startActivity(Intent(action)) }.isSuccess
        }
    }

    fun apply(change: TcpConfirmation) {
        if (!resumed || busy) return
        confirmation = null
        busy = true
        result = null
        operationFailed = false
        scope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    if (change.restore) TcpControlSession.manager.restoreInitial(change.expected)
                    else TcpControlSession.manager.apply(change.target, change.expected)
                }
                tcp = applied.state
                result = applied
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tcp = null
                operationFailed = true
            } finally {
                busy = false
            }
        }
    }

    PartsScaffold(title = stringResource(R.string.network_title), onBack = onBack) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            PrimaryTabRow(selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(R.string.network_wifi, R.string.network_sim, R.string.network_tcp).forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                        text = { Text(stringResource(title)) })
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = when (selectedTab) { 0 -> wifiScroll; 1 -> simScroll; else -> tcpScroll },
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            ) {
                if (settingsUnavailable) item("settings_error") {
                    PartsInfoCard(body = stringResource(R.string.network_settings_unavailable))
                }
                when (selectedTab) {
                    0 -> {
                        item("wifi") { WifiReadings(network.wifi) }
                        item("default_network") { DefaultNetworkReading(network.defaultNetwork) }
                        item("wifi_settings") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.network_wifi_settings),
                                    onClick = { openSettings(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS) })
                            }
                        }
                        item("wifi_help") { PartsInfoCard(body = stringResource(R.string.network_wifi_help)) }
                    }
                    1 -> {
                        if (!phonePermission || !network.mobile.permissionGranted) {
                            item("phone_permission") {
                                PartsInfoCard(body = stringResource(R.string.network_phone_permission_summary))
                                PartsPreferenceGroup {
                                    PartsPreference(title = stringResource(R.string.network_phone_permission),
                                        onClick = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) })
                                }
                            }
                        } else if (!network.mobile.available || network.mobile.sims.isEmpty()) {
                            item("no_sim") { PartsInfoCard(body = stringResource(
                                if (network.mobile.available) R.string.network_no_sim else R.string.network_mobile_unavailable)) }
                        } else {
                            network.mobile.sims.forEach { sim ->
                                item("sim_${sim.slotIndex}") { SimReadings(sim) }
                            }
                        }
                        item("mobile_settings") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.network_mobile_settings),
                                    onClick = { openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
                                        Settings.ACTION_WIRELESS_SETTINGS) })
                            }
                        }
                        item("mobile_help") { PartsInfoCard(body = stringResource(R.string.network_mobile_help)) }
                    }
                    2 -> {
                        item("tcp_help") { PartsInfoCard(body = stringResource(R.string.network_tcp_help)) }
                        item("tcp_current") {
                            PartsPreferenceGroup {
                                NetworkValue(R.string.network_tcp_current, tcp?.current?.let(::algorithmName))
                                NetworkValue(R.string.network_tcp_initial, tcp?.initial?.let(::algorithmName))
                            }
                        }
                        if (busy) item("tcp_busy") {
                            PartsInfoCard(body = stringResource(R.string.network_tcp_applying))
                        }
                        result?.let { applied ->
                            item("tcp_result") {
                                PartsInfoCard(body = if (applied.successful) stringResource(
                                    R.string.network_tcp_applied, algorithmName(applied.state.current.orEmpty()))
                                    else stringResource(tcpFailureString(applied.failure)))
                            }
                        }
                        if (operationFailed) item("tcp_failed") {
                            PartsInfoCard(body = stringResource(R.string.network_tcp_unavailable))
                        }
                        val state = tcp
                        if (state == null || !state.canApply) item("tcp_unavailable") {
                            PartsInfoCard(body = stringResource(if (state == null && !operationFailed)
                                R.string.network_loading else R.string.network_tcp_unavailable))
                        }
                        if (state != null && state.available.isNotEmpty()) item("tcp_choices") {
                            PartsPreferenceGroup(title = stringResource(R.string.network_tcp_algorithms)) {
                                state.available.forEach { algorithm ->
                                    PartsPreference(title = algorithmName(algorithm),
                                        summary = if (algorithm == state.current)
                                            stringResource(R.string.network_tcp_active) else null,
                                        selected = algorithm == state.current,
                                        enabled = resumed && !busy && state.canApply,
                                        onClick = {
                                            if (algorithm != state.current && !busy && resumed) {
                                                confirmation = TcpConfirmation(state, algorithm, false)
                                            }
                                        },
                                        trailing = { RadioButton(selected = algorithm == state.current,
                                            onClick = null, enabled = resumed && !busy && state.canApply) })
                                }
                            }
                        }
                        if (state?.canRestore == true) item("tcp_restore") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.network_tcp_restore),
                                    summary = state.initial?.let(::algorithmName), enabled = resumed && !busy,
                                    onClick = { state.initial?.let {
                                        confirmation = TcpConfirmation(state, it, true)
                                    } })
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { change ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (change.restore) R.string.network_tcp_restore else R.string.network_tcp_apply_title)) },
            text = { Text(stringResource(R.string.network_tcp_confirmation, algorithmName(change.target))) },
            confirmButton = {
                TextButton(enabled = resumed && !busy, onClick = { apply(change) }) {
                    Text(stringResource(R.string.network_apply))
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) {
                Text(stringResource(android.R.string.cancel))
            } },
        )
    }
}

@Composable
private fun WifiReadings(wifi: WifiSnapshot) {
    PartsPreferenceGroup(title = stringResource(R.string.network_wifi)) {
        NetworkValue(R.string.network_status, stringResource(when (wifi.status) {
            WifiStatus.UNAVAILABLE -> R.string.network_unavailable
            WifiStatus.DISABLED -> R.string.network_disabled
            WifiStatus.DISCONNECTED -> R.string.network_disconnected
            WifiStatus.CONNECTED -> R.string.network_connected
        }))
        if (wifi.status == WifiStatus.CONNECTED) {
            NetworkValue(R.string.network_signal, measurement(wifi.rssiDbm, R.string.network_dbm))
            NetworkValue(R.string.network_signal_level, measurement(wifi.level, R.string.network_level))
            NetworkValue(R.string.network_frequency, measurement(wifi.frequencyMhz, R.string.network_mhz))
            NetworkValue(R.string.network_standard, wifi.standard)
            NetworkValue(R.string.network_rx_link, measurement(wifi.rxLinkMbps, R.string.network_mbps))
            NetworkValue(R.string.network_tx_link, measurement(wifi.txLinkMbps, R.string.network_mbps))
        }
    }
}

@Composable
private fun DefaultNetworkReading(network: DefaultNetworkSnapshot) {
    val mobileLabel = stringResource(R.string.network_mobile)
    PartsPreferenceGroup(title = stringResource(R.string.network_default_connection)) {
        NetworkValue(R.string.network_status, stringResource(when {
            !network.connected -> R.string.network_disconnected
            network.validated -> R.string.network_validated
            else -> R.string.network_not_validated
        }))
        if (network.connected) {
            NetworkValue(R.string.network_transport, network.transports.joinToString { transport ->
                if (transport == "Cellular") mobileLabel else transport
            }.takeIf { it.isNotEmpty() })
        }
    }
}

@Composable
private fun SimReadings(sim: SimSnapshot) {
    PartsPreferenceGroup(title = stringResource(R.string.network_sim_slot, sim.slotIndex + 1)) {
        if (sim.isDefaultData) NetworkValue(R.string.network_mobile_data, stringResource(R.string.network_default_sim))
        NetworkValue(R.string.network_status, stringResource(when (sim.serviceState) {
            MobileServiceState.UNKNOWN -> R.string.network_unavailable
            MobileServiceState.IN_SERVICE -> R.string.network_in_service
            MobileServiceState.OUT_OF_SERVICE -> R.string.network_out_of_service
            MobileServiceState.EMERGENCY_ONLY -> R.string.network_emergency_only
            MobileServiceState.POWER_OFF -> R.string.network_radio_off
        }))
        NetworkValue(R.string.network_data_type, sim.dataNetworkType)
        NetworkValue(R.string.network_display_type, sim.displayNetworkType)
    }
    if (sim.signals.isEmpty()) PartsInfoCard(body = stringResource(R.string.network_no_signal))
    sim.signals.forEach { signal ->
        PartsPreferenceGroup(title = signal.technology) {
            NetworkValue(R.string.network_signal, measurement(signal.dbm, R.string.network_dbm))
            NetworkValue(R.string.network_signal_level, measurement(signal.level, R.string.network_level))
            if (signal.technology == "LTE" || signal.technology == "NR") {
                NetworkValue(if (signal.technology == "NR") R.string.network_ss_rsrp else R.string.network_rsrp,
                    measurement(signal.rsrpDbm, R.string.network_dbm))
                NetworkValue(if (signal.technology == "NR") R.string.network_ss_rsrq else R.string.network_rsrq,
                    measurement(signal.rsrqDb, R.string.network_db))
                NetworkValue(if (signal.technology == "NR") R.string.network_ss_sinr else R.string.network_sinr,
                    measurement(signal.sinrDb, R.string.network_db))
            }
        }
    }
}

@Composable
private fun NetworkValue(label: Int, value: String?) {
    PartsPreference(title = stringResource(label), summary = value ?: stringResource(R.string.network_unavailable))
}

@Composable
private fun measurement(value: Int?, format: Int): String? = value?.let { stringResource(format, it) }

private fun hasPhonePermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

private fun algorithmName(algorithm: String): String = when (algorithm) {
    "reno" -> "Reno"
    "cubic" -> "CUBIC"
    "bic" -> "BIC"
    "westwood" -> "Westwood+"
    "htcp" -> "H-TCP"
    "bbr" -> "BBR"
    "bbr3" -> "BBR3"
    else -> algorithm
}

private fun tcpFailureString(failure: TcpFailure?): Int = when (failure) {
    TcpFailure.INVALID_ALGORITHM -> R.string.network_tcp_invalid
    TcpFailure.CONFLICT -> R.string.network_tcp_conflict
    TcpFailure.WRITE_FAILED -> R.string.network_tcp_write_failed
    TcpFailure.READBACK_FAILED -> R.string.network_tcp_readback_failed
    TcpFailure.NOT_OWNED -> R.string.network_tcp_not_owned
    else -> R.string.network_tcp_unavailable
}
