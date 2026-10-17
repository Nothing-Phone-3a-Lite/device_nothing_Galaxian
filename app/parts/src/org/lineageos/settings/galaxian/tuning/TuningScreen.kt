/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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

private data class TuningConfirmation(val text: String, val action: () -> Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumed = rememberPartsResumed()
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var gov by remember { mutableStateOf(CpuGovState()) }
    var gpuGov by remember { mutableStateOf(GpuGovState()) }
    var io by remember { mutableStateOf(emptyList<IoDevice>()) }
    var vm by remember { mutableStateOf(VmState()) }
    var uclamp by remember { mutableStateOf(UclampState()) }
    var bore by remember { mutableStateOf(BoreState()) }
    var lmk by remember { mutableStateOf(LmkState()) }
    var diag by remember { mutableStateOf(DiagState()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<TuningConfirmation?>(null) }
    var profileReport by remember { mutableStateOf<ProfileApplyReport?>(null) }
    var profileError by remember { mutableStateOf(false) }
    val tabScrolls = List(6) { rememberLazyListState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { ProfileManager.export().toJson().toString(2) }
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: throw IllegalStateException("export failed")
                result = context.getString(R.string.tuning_profile_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = context.getString(R.string.tuning_error_write)
            } finally {
                busy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        profileReport = null
        profileError = false
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }
                val profile = raw?.let(TuningProfile::parse)
                if (profile == null) {
                    profileError = true
                } else {
                    profileReport = withContext(Dispatchers.IO) { ProfileManager.apply(profile) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                profileError = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(resumed, selectedTab) {
        confirmation = null
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            withContext(Dispatchers.IO) {
                when (selectedTab) {
                    0 -> gov = TuningSession.cpuGov.readState()
                    1 -> gpuGov = TuningSession.gpuGov.readState()
                    2 -> io = TuningSession.io.readState()
                    3 -> {
                        vm = TuningSession.vm.readState()
                        lmk = LmkManager().readState()
                    }
                    4 -> {
                        uclamp = TuningSession.uclamp.readState()
                        bore = BoreManager().readState()
                        diag = DiagManager().readState()
                    }
                    else -> Unit
                }
            }
            delay(1_000)
        }
    }

    fun run(block: () -> Boolean) {
        if (!resumed || busy) return
        busy = true
        result = null
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { block() }
                result = context.getString(if (ok) R.string.tuning_verified
                    else R.string.tuning_error_write)
                withContext(Dispatchers.IO) {
                    gov = TuningSession.cpuGov.readState()
                    gpuGov = TuningSession.gpuGov.readState()
                    io = TuningSession.io.readState()
                    vm = TuningSession.vm.readState()
                    uclamp = TuningSession.uclamp.readState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = context.getString(R.string.tuning_error_write)
            } finally {
                busy = false
            }
        }
    }

    PartsScaffold(title = stringResource(R.string.tuning_title), onBack = onBack) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            PrimaryTabRow(selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(R.string.tuning_tab_cpu, R.string.tuning_tab_gpu, R.string.tuning_tab_io,
                    R.string.tuning_tab_memory, R.string.tuning_tab_system,
                    R.string.tuning_tab_profiles).forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                        text = { Text(stringResource(title)) })
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = tabScrolls[selectedTab],
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            ) {
                item("reboot_note") {
                    PartsInfoCard(body = stringResource(R.string.tuning_reboot_note))
                }
                when (selectedTab) {
                    0 -> {
                        if (gov.policies.isEmpty()) {
                            item("gov_empty") { PartsInfoCard(
                                body = stringResource(R.string.tuning_unsupported)) }
                        }
                        gov.policies.forEach { policy ->
                            item("gov_${policy.id}") {
                                PartsPreferenceGroup(
                                    title = stringResource(if (policy.id == 0)
                                        R.string.cpu_efficiency_cluster
                                    else R.string.cpu_performance_cluster)) {
                                    if (policy.available.isEmpty()) {
                                        PartsPreference(title = stringResource(
                                            R.string.tuning_current_governor),
                                            summary = policy.governor
                                                ?: stringResource(R.string.tuning_unavailable))
                                    } else {
                                        policy.available.forEach { governor ->
                                            PartsPreference(title = governor,
                                                selected = governor == policy.governor,
                                                enabled = resumed && !busy && policy.writable,
                                                onClick = {
                                                    if (governor != policy.governor) {
                                                        confirmation = TuningConfirmation(governor) {
                                                            run {
                                                                TuningSession.cpuGov.setGovernor(
                                                                    policy.id, governor).successful
                                                            }
                                                        }
                                                    }
                                                },
                                                trailing = { RadioButton(
                                                    selected = governor == policy.governor,
                                                    onClick = null,
                                                    enabled = resumed && !busy && policy.writable) })
                                        }
                                    }
                                }
                            }
                        }
                        item("schedutil") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_schedutil)) {
                                if (!gov.schedutilPresent) {
                                    PartsPreference(title = stringResource(
                                        R.string.tuning_schedutil_rate),
                                        summary = stringResource(R.string.tuning_unsupported))
                                } else {
                                    val rate = gov.policies.firstOrNull()?.rateLimitUs
                                    PartsPreference(title = stringResource(R.string.tuning_schedutil_rate),
                                        summary = rate?.let {
                                            stringResource(R.string.tuning_microseconds, it)
                                        } ?: stringResource(R.string.tuning_unavailable))
                                    RATE_CHOICES.forEach { choice ->
                                        PartsPreference(title = stringResource(
                                            R.string.tuning_microseconds, choice),
                                            selected = rate == choice,
                                            enabled = resumed && !busy &&
                                                (gov.policies.firstOrNull()?.rateWritable == true),
                                            onClick = {
                                                confirmation = TuningConfirmation("$choice") {
                                                    run {
                                                        TuningSession.cpuGov.setRateLimitUs(
                                                            choice).successful
                                                    }
                                                }
                                            },
                                            trailing = { RadioButton(selected = rate == choice,
                                                onClick = null, enabled = resumed && !busy &&
                                                    (gov.policies.firstOrNull()?.rateWritable == true)) })
                                    }
                                }
                            }
                        }
                        item("gov_restore") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.tuning_restore),
                                    summary = stringResource(R.string.tuning_restore_summary),
                                    enabled = resumed && !busy && gov.policies.isNotEmpty(),
                                    onClick = { run {
                                        TuningSession.cpuGov.restoreInitial().successful
                                    } })
                            }
                        }
                    }
                    1 -> {
                        if (gpuGov.devices.isEmpty()) {
                            item("gpugov_empty") { PartsInfoCard(
                                title = stringResource(R.string.tuning_gpu_governor),
                                body = stringResource(R.string.tuning_gpu_unsupported)) }
                        }
                        gpuGov.devices.forEach { device ->
                            item("gpugov_${device.name}") {
                                PartsPreferenceGroup(title = device.name) {
                                    device.available.forEach { governor ->
                                        PartsPreference(title = governor,
                                            selected = governor == device.governor,
                                            enabled = resumed && !busy && device.writable,
                                            onClick = {
                                                if (governor != device.governor) {
                                                    confirmation = TuningConfirmation(governor) {
                                                        run {
                                                            TuningSession.gpuGov.setGovernor(
                                                                device.name, governor).successful
                                                        }
                                                    }
                                                }
                                            },
                                            trailing = { RadioButton(
                                                selected = governor == device.governor,
                                                onClick = null,
                                                enabled = resumed && !busy && device.writable) })
                                    }
                                }
                            }
                        }
                        if (gpuGov.devices.isNotEmpty()) item("gpugov_restore") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.tuning_restore),
                                    enabled = resumed && !busy,
                                    onClick = { run {
                                        TuningSession.gpuGov.restoreInitial().successful
                                    } })
                            }
                        }
                    }
                    2 -> {
                        if (io.isEmpty()) {
                            item("io_empty") { PartsInfoCard(
                                body = stringResource(R.string.tuning_unsupported)) }
                        }
                        io.forEach { device ->
                            item("io_${device.name}") {
                                PartsPreferenceGroup(title = stringResource(
                                    R.string.tuning_io_device, device.name)) {
                                    if (device.available.isNotEmpty()) {
                                        device.available.forEach { scheduler ->
                                            PartsPreference(title = scheduler,
                                                selected = scheduler == device.scheduler,
                                                enabled = resumed && !busy && device.writable,
                                                onClick = {
                                                    if (scheduler != device.scheduler) {
                                                        confirmation =
                                                            TuningConfirmation(scheduler) {
                                                                run {
                                                                    TuningSession.io.setScheduler(
                                                                        device.name,
                                                                        scheduler).successful
                                                                }
                                                            }
                                                    }
                                                },
                                                trailing = { RadioButton(
                                                    selected = scheduler == device.scheduler,
                                                    onClick = null,
                                                    enabled = resumed && !busy && device.writable) })
                                        }
                                    }
                                    PartsPreference(title = stringResource(
                                        R.string.tuning_read_ahead),
                                        summary = device.readAheadKb?.let {
                                            stringResource(R.string.tuning_kilobytes, it)
                                        } ?: stringResource(R.string.tuning_unavailable))
                                    IoManager.READ_AHEAD_CHOICES.forEach { choice ->
                                        PartsPreference(title = stringResource(
                                            R.string.tuning_kilobytes, choice),
                                            selected = device.readAheadKb == choice,
                                            enabled = resumed && !busy && device.writable,
                                            onClick = {
                                                confirmation = TuningConfirmation("$choice") {
                                                    run {
                                                        TuningSession.io.setReadAheadKb(
                                                            device.name, choice).successful
                                                    }
                                                }
                                            },
                                            trailing = { RadioButton(
                                                selected = device.readAheadKb == choice,
                                                onClick = null,
                                                enabled = resumed && !busy && device.writable) })
                                    }
                                }
                            }
                        }
                        if (io.isNotEmpty()) item("io_restore") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.tuning_restore),
                                    enabled = resumed && !busy,
                                    onClick = { run {
                                        TuningSession.io.restoreInitial().successful
                                    } })
                            }
                        }
                    }
                    3 -> {
                        item("vm_help") { PartsInfoCard(
                            body = stringResource(R.string.tuning_vm_help)) }
                        item("vm_values") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_vm)) {
                                PartsPreference(
                                    title = stringResource(R.string.tuning_swappiness),
                                    summary = stringResource(R.string.tuning_swappiness_vendor))
                                VmValue(R.string.tuning_dirty_ratio, vm.dirtyRatio)
                                VmValue(R.string.tuning_dirty_background, vm.dirtyBackground)
                                VmValue(R.string.tuning_cache_pressure, vm.cachePressure)
                            }
                        }
                        item("vm_presets") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_vm_presets)) {
                                if (!vm.canApply && vm.dirtyRatio == null) {
                                    PartsPreference(title = stringResource(
                                        R.string.tuning_vm_presets),
                                        summary = stringResource(R.string.tuning_unavailable))
                                } else {
                                    VmPreset.entries.forEach { preset ->
                                        PartsPreference(title = stringResource(vmPresetLabel(preset)),
                                            summary = stringResource(vmPresetSummary(preset)),
                                            selected = vm.activePreset == preset,
                                            enabled = resumed && !busy && vm.canApply,
                                            onClick = {
                                                if (vm.activePreset != preset) {
                                                    confirmation = TuningConfirmation(
                                                        context.getString(vmPresetLabel(preset))) {
                                                        val state = TuningSession.vm.readState()
                                                        val applied = TuningSession.vm.applyPreset(
                                                            preset, state)
                                                        vm = applied.state
                                                        applied.successful
                                                    }
                                                }
                                            },
                                            trailing = { RadioButton(
                                                selected = vm.activePreset == preset,
                                                onClick = null,
                                                enabled = resumed && !busy && vm.canApply) })
                                    }
                                }
                            }
                        }
                        item("lmk") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_lmk)) {
                                if (lmk.driverPresent) {
                                    PartsPreference(title = stringResource(R.string.tuning_lmk_driver),
                                        summary = stringResource(R.string.tuning_lmk_driver_present))
                                } else {
                                    PartsPreference(title = stringResource(R.string.tuning_lmk_driver),
                                        summary = stringResource(R.string.tuning_lmk_driver_gone))
                                }
                                if (lmk.props.isEmpty()) {
                                    PartsPreference(title = stringResource(
                                        R.string.tuning_lmk_config),
                                        summary = stringResource(R.string.tuning_unavailable))
                                } else {
                                    lmk.props.toSortedMap().forEach { (name, value) ->
                                        PartsPreference(title = name.removePrefix("ro.lmk."),
                                            summary = value)
                                    }
                                }
                            }
                        }
                        item("zram_note") { PartsInfoCard(
                            title = stringResource(R.string.tuning_zram),
                            body = stringResource(R.string.tuning_zram_note)) }
                    }
                    4 -> {
                        item("uclamp_help") { PartsInfoCard(
                            body = stringResource(R.string.tuning_uclamp_help)) }
                        item("uclamp_values") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_uclamp)) {
                                PartsPreference(title = stringResource(R.string.tuning_uclamp_min),
                                    summary = uclamp.min?.toString()
                                        ?: stringResource(R.string.tuning_unavailable))
                                PartsPreference(title = stringResource(R.string.tuning_uclamp_max),
                                    summary = uclamp.max?.toString()
                                        ?: stringResource(R.string.tuning_unavailable))
                                if (uclamp.canApply || uclamp.min != null) {
                                    UclampPreset.entries.forEach { preset ->
                                        PartsPreference(
                                            title = stringResource(uclampPresetLabel(preset)),
                                            selected = uclamp.activePreset == preset,
                                            enabled = resumed && !busy && uclamp.canApply,
                                            onClick = {
                                                if (uclamp.activePreset != preset) {
                                                    confirmation = TuningConfirmation(
                                                        context.getString(
                                                            uclampPresetLabel(preset))) {
                                                        val state =
                                                            TuningSession.uclamp.readState()
                                                        val applied =
                                                            TuningSession.uclamp.applyPreset(
                                                                preset, state)
                                                        uclamp = applied.state
                                                        applied.successful
                                                    }
                                                }
                                            },
                                            trailing = { RadioButton(
                                                selected = uclamp.activePreset == preset,
                                                onClick = null,
                                                enabled = resumed && !busy && uclamp.canApply) })
                                    }
                                }
                            }
                        }
                        item("bore") {
                            PartsPreferenceGroup(title = stringResource(R.string.tuning_bore)) {
                                PartsPreference(title = stringResource(R.string.tuning_bore_status),
                                    summary = if (bore.supported) {
                                        bore.detail ?: stringResource(R.string.tuning_available)
                                    } else {
                                        stringResource(R.string.tuning_bore_unsupported)
                                    })
                            }
                        }
                        item("diag") {
                            PartsPreferenceGroup(
                                title = stringResource(R.string.tuning_diagnostics)) {
                                PartsPreference(title = stringResource(R.string.tuning_loadavg),
                                    summary = if (diag.load1 == null) {
                                        stringResource(R.string.tuning_unavailable)
                                    } else {
                                        stringResource(R.string.tuning_load_format,
                                            diag.load1 ?: 0.0, diag.load5 ?: 0.0,
                                            diag.load15 ?: 0.0)
                                    })
                                PartsPreference(title = stringResource(R.string.tuning_tasks),
                                    summary = if (diag.runningTasks == null) {
                                        stringResource(R.string.tuning_unavailable)
                                    } else {
                                        stringResource(R.string.tuning_tasks_format,
                                            diag.runningTasks ?: 0, diag.totalTasks ?: 0)
                                    })
                                if (!diag.psiSupported) {
                                    PartsPreference(title = stringResource(R.string.tuning_psi),
                                        summary = stringResource(R.string.tuning_psi_unavailable))
                                } else {
                                    PsiValue(R.string.tuning_psi_cpu, diag.psiCpu)
                                    PsiValue(R.string.tuning_psi_memory, diag.psiMemory)
                                    PsiValue(R.string.tuning_psi_io, diag.psiIo)
                                }
                            }
                        }
                    }
                    else -> {
                        item("profile_help") { PartsInfoCard(
                            body = stringResource(R.string.tuning_profile_help)) }
                        item("profile_actions") {
                            PartsPreferenceGroup {
                                PartsPreference(title = stringResource(R.string.tuning_export),
                                    summary = stringResource(R.string.tuning_export_summary),
                                    enabled = resumed && !busy,
                                    onClick = { exportLauncher.launch("galaxian-tuning.json") })
                                PartsPreference(title = stringResource(R.string.tuning_import),
                                    summary = stringResource(R.string.tuning_import_summary),
                                    enabled = resumed && !busy,
                                    onClick = { importLauncher.launch(arrayOf("application/json")) })
                            }
                        }
                        if (busy) item("profile_busy") {
                            PartsInfoCard(body = stringResource(R.string.tuning_applying))
                        }
                        if (profileError) item("profile_error") {
                            PartsInfoCard(body = stringResource(R.string.tuning_import_invalid))
                        }
                        profileReport?.let { report ->
                            if (!report.empty) item("profile_report") {
                                PartsInfoCard(
                                    title = stringResource(R.string.tuning_import_result),
                                    body = stringResource(R.string.tuning_import_result_format,
                                        report.applied.joinToString().ifEmpty { "—" },
                                        report.failed.joinToString().ifEmpty { "—" }))
                            }
                        }
                    }
                }
                if (busy && selectedTab != 5) item("op_busy") {
                    PartsInfoCard(body = stringResource(R.string.tuning_applying))
                }
                result?.let {
                    item("op_result") { PartsInfoCard(body = it) }
                }
            }
        }
    }

    confirmation?.let { change ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.tuning_confirm_title)) },
            text = { Text(stringResource(R.string.tuning_confirm_format, change.text)) },
            confirmButton = {
                TextButton(enabled = resumed && !busy, onClick = {
                    val action = change.action
                    confirmation = null
                    run(action)
                }) { Text(stringResource(R.string.tuning_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) {
                Text(stringResource(android.R.string.cancel))
            } },
        )
    }
}

@Composable
private fun VmValue(label: Int, value: Int?) {
    PartsPreference(title = stringResource(label),
        summary = value?.toString() ?: stringResource(R.string.tuning_unavailable))
}

@Composable
private fun PsiValue(label: Int, line: PsiLine?) {
    PartsPreference(title = stringResource(label),
        summary = line?.let {
            val avg = it.avg10 ?: it.avg60 ?: it.avg300
            listOfNotNull(
                avg?.let { v -> "%.2f".format(v) },
                it.total?.let { t -> "total $t" },
            ).joinToString(", ").ifEmpty { null }
        } ?: stringResource(R.string.tuning_unavailable))
}

private fun vmPresetLabel(preset: VmPreset): Int = when (preset) {
    VmPreset.STOCK -> R.string.tuning_preset_stock
    VmPreset.BALANCED -> R.string.tuning_preset_balanced
    VmPreset.PERFORMANCE -> R.string.tuning_preset_performance
    VmPreset.BATTERY -> R.string.tuning_preset_battery
}

private fun vmPresetSummary(preset: VmPreset): Int = when (preset) {
    VmPreset.STOCK -> R.string.tuning_preset_stock_summary
    VmPreset.BALANCED -> R.string.tuning_preset_balanced_summary
    VmPreset.PERFORMANCE -> R.string.tuning_preset_performance_summary
    VmPreset.BATTERY -> R.string.tuning_preset_battery_summary
}

private fun uclampPresetLabel(preset: UclampPreset): Int = when (preset) {
    UclampPreset.DEFAULT -> R.string.tuning_preset_default
    UclampPreset.BATTERY -> R.string.tuning_preset_battery
    UclampPreset.PERFORMANCE -> R.string.tuning_preset_performance
}

private val RATE_CHOICES = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)
