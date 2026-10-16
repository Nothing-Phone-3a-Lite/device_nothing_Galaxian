/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import android.os.SystemProperties
import java.io.File

internal data class VmResult(
    val state: VmState,
    val failure: TuningFailure? = null,
    val writeAttempted: Boolean = false,
) {
    val successful: Boolean get() = failure == null
}

internal data class UclampResult(
    val state: UclampState,
    val failure: TuningFailure? = null,
    val writeAttempted: Boolean = false,
) {
    val successful: Boolean get() = failure == null
}

/** Init applies the preset asynchronously, so success requires readback like TCP. */
internal interface VmPort {
    fun controlReady(): Boolean
    fun requestPreset(preset: VmPreset)
}

internal interface UclampPort {
    fun controlReady(): Boolean
    fun requestPreset(preset: UclampPreset)
}

internal class VmManager(
    private val port: VmPort,
    private val procVm: File = File("/proc/sys/vm"),
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var initial: VmPreset? = null

    @Synchronized fun readState(): VmState = snapshot()

    @Synchronized
    fun applyPreset(target: VmPreset, expected: VmState): VmResult {
        val before = snapshot()
        if (!before.canApply) return VmResult(before, TuningFailure.UNAVAILABLE)
        if (!sameValues(before, expected)) return VmResult(before, TuningFailure.CONFLICT)
        if (before.activePreset == target) return VmResult(before)
        return applyChecked(target, before)
    }

    @Synchronized
    fun restoreStock(expected: VmState): VmResult {
        val before = snapshot()
        if (!sameValues(before, expected)) return VmResult(before, TuningFailure.CONFLICT)
        if (!before.canApply) return VmResult(before, TuningFailure.UNAVAILABLE)
        if (before.activePreset == VmPreset.STOCK) return VmResult(before)
        return applyChecked(VmPreset.STOCK, before)
    }

    private fun applyChecked(target: VmPreset, before: VmState): VmResult {
        try {
            port.requestPreset(target)
        } catch (_: Exception) {
            return VmResult(snapshot(), TuningFailure.WRITE_FAILED, writeAttempted = true)
        }
        repeat(26) { attempt ->
            val observed = snapshot()
            if (!observed.canApply) {
                return VmResult(observed, TuningFailure.READBACK_FAILED, writeAttempted = true)
            }
            if (!sameValues(observed, before) && observed.activePreset != target) {
                return VmResult(observed, TuningFailure.CONFLICT, writeAttempted = true)
            }
            if (observed.activePreset == target) return VmResult(observed, writeAttempted = true)
            if (attempt < 25) {
                try {
                    pause(40)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return VmResult(snapshot(), TuningFailure.READBACK_FAILED, true)
                }
            }
        }
        return VmResult(snapshot(), TuningFailure.READBACK_FAILED, writeAttempted = true)
    }

    private fun snapshot(): VmState {
        val values = mapOf(
            "swappiness" to readInt("swappiness", 0..100),
            "dirty_ratio" to readInt("dirty_ratio", 0..100),
            "dirty_background_ratio" to readInt("dirty_background_ratio", 0..100),
            "vfs_cache_pressure" to readInt("vfs_cache_pressure", 0..1000),
        )
        val ready = runCatching { port.controlReady() }.getOrDefault(false)
        val complete = values.values.all { it != null }
        val active = VmPreset.entries.firstOrNull { preset ->
            preset.swappiness == values["swappiness"] &&
                preset.dirtyRatio == values["dirty_ratio"] &&
                preset.dirtyBackground == values["dirty_background_ratio"] &&
                preset.cachePressure == values["vfs_cache_pressure"]
        }
        if (initial == null) initial = active
        val applicable = complete && ready
        return VmState(
            swappiness = values["swappiness"],
            dirtyRatio = values["dirty_ratio"],
            dirtyBackground = values["dirty_background_ratio"],
            cachePressure = values["vfs_cache_pressure"],
            activePreset = active,
            canApply = applicable,
        )
    }

    private fun sameValues(a: VmState, b: VmState): Boolean =
        a.swappiness == b.swappiness && a.dirtyRatio == b.dirtyRatio &&
            a.dirtyBackground == b.dirtyBackground && a.cachePressure == b.cachePressure

    private fun readInt(node: String, range: IntRange): Int? =
        readSmallInt(File(procVm, node))?.takeIf { it in range }
}

internal class UclampManager(
    private val port: UclampPort,
    private val procKernel: File = File("/proc/sys/kernel"),
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Synchronized fun readState(): UclampState = snapshot()

    @Synchronized
    fun applyPreset(target: UclampPreset, expected: UclampState): UclampResult {
        val before = snapshot()
        if (!before.canApply) return UclampResult(before, TuningFailure.UNAVAILABLE)
        if (before.min != expected.min || before.max != expected.max) {
            return UclampResult(before, TuningFailure.CONFLICT)
        }
        if (before.activePreset == target) return UclampResult(before)
        try {
            port.requestPreset(target)
        } catch (_: Exception) {
            return UclampResult(snapshot(), TuningFailure.WRITE_FAILED, writeAttempted = true)
        }
        repeat(26) { attempt ->
            val observed = snapshot()
            if (!observed.canApply) {
                return UclampResult(observed, TuningFailure.READBACK_FAILED, writeAttempted = true)
            }
            if (observed.activePreset == target) return UclampResult(observed, writeAttempted = true)
            if (observed.min != before.min || observed.max != before.max) {
                return UclampResult(observed, TuningFailure.CONFLICT, writeAttempted = true)
            }
            if (attempt < 25) {
                try {
                    pause(40)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return UclampResult(snapshot(), TuningFailure.READBACK_FAILED, true)
                }
            }
        }
        return UclampResult(snapshot(), TuningFailure.READBACK_FAILED, writeAttempted = true)
    }

    private fun snapshot(): UclampState {
        val min = readSmallInt(File(procKernel, "sched_uclamp_util_min"))?.takeIf { it in 0..1024 }
        val max = readSmallInt(File(procKernel, "sched_uclamp_util_max"))?.takeIf { it in 0..1024 }
        val ready = runCatching { port.controlReady() }.getOrDefault(false)
        val applicable = min != null && max != null && min <= max && ready
        return UclampState(
            min = min,
            max = max,
            activePreset = UclampPreset.entries.firstOrNull { it.min == min && it.max == max },
            canApply = applicable,
        )
    }
}

/** Init ports. Property writes are requests; the kernel files hold the truth. */
internal object TuningSession {
    val vm = VmManager(object : VmPort {
        override fun controlReady() = SystemProperties.get("sys.galaxianparts.tuning_ready") == "1"
        override fun requestPreset(preset: VmPreset) {
            SystemProperties.set("sys.galaxianparts.vm_profile", preset.name.lowercase())
        }
    })
    val uclamp = UclampManager(object : UclampPort {
        override fun controlReady() = SystemProperties.get("sys.galaxianparts.tuning_ready") == "1"
        override fun requestPreset(preset: UclampPreset) {
            SystemProperties.set("sys.galaxianparts.uclamp_profile", preset.name.lowercase())
        }
    })
    val cpuGov = CpuGovManager()
    val gpuGov = GpuGovManager()
    val io = IoManager()
}
