/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import org.lineageos.settings.galaxian.cpu.CpuControlSession
import org.lineageos.settings.galaxian.gpu.GpuControlSession
import org.lineageos.settings.galaxian.network.TcpControlSession

internal data class ProfileApplyReport(
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val empty: Boolean get() = applied.isEmpty() && failed.isEmpty()
}

/**
 * Export/import of tuning setups as versioned JSON. Import applies section by
 * section through the same managers as the UI and reports each outcome; a
 * section the kernel rejects is reported, never silently skipped as success.
 */
internal object ProfileManager {
    fun export(): TuningProfile {
        val cpuState = runCatching { CpuControlSession.read { it.readState() } }.getOrNull()
        val govState = runCatching { TuningSession.cpuGov.readState() }.getOrNull()
        val cpu = cpuState?.policies?.associate { policy ->
            policy.id.toString() to CpuProfileEntry(
                min = policy.minimum,
                max = policy.maximum,
                governor = govState?.policies?.firstOrNull { it.id == policy.id }?.governor,
            )
        }.orEmpty()
        val gpuState = runCatching { GpuControlSession.snapshot().state }.getOrNull()
        val tcpState = runCatching { TcpControlSession.manager.readState() }.getOrNull()
        val vmState = runCatching { TuningSession.vm.readState() }.getOrNull()
        val uclampState = runCatching { TuningSession.uclamp.readState() }.getOrNull()
        val io = runCatching { TuningSession.io.readState() }.getOrNull().orEmpty()
            .associate { device ->
                device.name to IoProfileEntry(device.scheduler, device.readAheadKb)
            }
        return TuningProfile(
            cpu = cpu,
            gpu = gpuState?.let {
                val min = it.requestedMinimumKHz ?: it.fixedKHz
                val max = it.requestedMaximumKHz ?: it.fixedKHz
                if (min == null && max == null) null else GpuProfileEntry(min, max)
            },
            tcp = tcpState?.current,
            vm = vmState?.activePreset?.name?.lowercase(),
            uclamp = uclampState?.activePreset?.name?.lowercase(),
            io = io,
        )
    }

    fun apply(profile: TuningProfile): ProfileApplyReport {
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        profile.cpu.forEach { (idText, entry) ->
            val id = idText.toIntOrNull() ?: return@forEach
            val label = "cpu$id"
            val rangeResult = runCatching {
                val state = CpuControlSession.read { it.readState() }
                val policy = state.policies.firstOrNull { it.id == id }
                    ?: return@runCatching false
                val min = entry.min?.takeIf { it in policy.frequencies } ?: policy.minimum
                val max = entry.max?.takeIf { it in policy.frequencies } ?: policy.maximum
                CpuControlSession.manual {
                    it.setFrequencyRange(id, min, max, force = policy.lockSupported)
                }.successful
            }.getOrDefault(false)
            val govResult = entry.governor?.let { governor ->
                runCatching { TuningSession.cpuGov.setGovernor(id, governor).successful }
                    .getOrDefault(false)
            } ?: true
            if (rangeResult && govResult) applied += label else failed += label
        }
        profile.gpu?.let { entry ->
            val ok = runCatching {
                val state = GpuControlSession.snapshot().state
                val table = state.frequenciesKHz
                val min = entry.min?.takeIf { it in table }
                    ?: state.requestedMinimumKHz ?: state.fixedKHz ?: table.minOrNull()
                val max = entry.max?.takeIf { it in table }
                    ?: state.requestedMaximumKHz ?: state.fixedKHz ?: table.maxOrNull()
                if (min == null || max == null || table.isEmpty()) return@runCatching false
                GpuControlSession.manual { it.setFrequencyRange(min, max) }.successful
            }.getOrDefault(false)
            if (ok) applied += "gpu" else failed += "gpu"
        }
        profile.tcp?.let { algorithm ->
            val ok = runCatching {
                val state = TcpControlSession.manager.readState()
                if (algorithm !in state.available) return@runCatching false
                TcpControlSession.manager.apply(algorithm, state).successful
            }.getOrDefault(false)
            if (ok) applied += "tcp" else failed += "tcp"
        }
        profile.vm?.let { name ->
            val preset = runCatching { VmPreset.valueOf(name.uppercase()) }.getOrNull()
            val ok = preset != null && runCatching {
                TuningSession.vm.applyPreset(preset, TuningSession.vm.readState()).successful
            }.getOrDefault(false)
            if (ok) applied += "vm" else failed += "vm"
        }
        profile.uclamp?.let { name ->
            val preset = runCatching { UclampPreset.valueOf(name.uppercase()) }.getOrNull()
            val ok = preset != null && runCatching {
                TuningSession.uclamp.applyPreset(preset, TuningSession.uclamp.readState()).successful
            }.getOrDefault(false)
            if (ok) applied += "uclamp" else failed += "uclamp"
        }
        profile.io.forEach { (device, entry) ->
            var ok = true
            entry.scheduler?.let { scheduler ->
                ok = runCatching {
                    TuningSession.io.setScheduler(device, scheduler).successful
                }.getOrDefault(false) && ok
            }
            entry.readAheadKb?.let { readAheadKb ->
                ok = runCatching {
                    TuningSession.io.setReadAheadKb(device, readAheadKb).successful
                }.getOrDefault(false) && ok
            }
            if (ok) applied += "io:$device" else failed += "io:$device"
        }
        return ProfileApplyReport(applied.sorted(), failed.sorted())
    }
}
