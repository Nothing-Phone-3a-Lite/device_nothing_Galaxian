/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import android.os.SystemProperties
import java.io.File

/**
 * BORE scheduler presets. The 6.1 Galaxian kernel is not expected to carry BORE, so
 * this probes the known nodes and reports absence instead of inventing support.
 * A BORE-capable kernel shows its state here; unknown layouts stay read-only.
 */
internal class BoreManager(
    private val procKernel: File = File("/proc/sys/kernel"),
    private val sysKernel: File = File("/sys/kernel"),
) {
    fun readState(): BoreState {
        val markers = PROBE_PATHS.mapNotNull { relative ->
            val base = if (relative.startsWith("sys:")) sysKernel else procKernel
            val file = File(base, relative.removePrefix("sys:").removePrefix("proc:"))
            readSmallText(file, 128)?.let { relative to it }
        }.toMap()
        if (markers.isEmpty()) return BoreState(supported = false)
        return BoreState(supported = true, detail = markers.entries.joinToString("\n") { (k, v) -> "$k=$v" })
    }

    companion object {
        private val PROBE_PATHS = listOf(
            "proc:sched_bore",
            "sys:bore/sched_bore",
            "sys:bore/bore_enabled",
        )
    }
}

/**
 * Low-memory killer status. The in-kernel LMK driver is gone on 6.1; lmkd owns
 * reclaim policy from its read-only properties. This page reports that state and
 * does not pretend minfree tuning exists.
 */
internal class LmkManager(
    private val moduleRoot: File = File("/sys/module"),
) {
    fun readState(): LmkState {
        val driverPresent = File(moduleRoot, "lowmemorykiller").isDirectory ||
            File(moduleRoot, "lowmemorykiller").isFile
        val props = LMK_PROPS.mapNotNull { name ->
            runCatching { SystemProperties.get(name) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { name to it }
        }.toMap()
        return LmkState(driverPresent = driverPresent, props = props)
    }

    companion object {
        internal val LMK_PROPS = listOf(
            "ro.lmk.low",
            "ro.lmk.medium",
            "ro.lmk.critical",
            "ro.lmk.critical_upgrade",
            "ro.lmk.upgrade_pressure",
            "ro.lmk.downgrade_pressure",
            "ro.lmk.kill_heaviest_task",
            "ro.lmk.kill_timeout_ms",
            "ro.lmk.use_minfree_levels",
        )
    }
}

/**
 * Visible effect per tuning: load average and PSI stalls. Read-only; absent
 * pressure files mean the kernel does not account stalls, not zero stalls.
 */
internal class DiagManager(
    private val procRoot: File = File("/proc"),
) {
    fun readState(): DiagState {
        val load = readSmallText(File(procRoot, "loadavg"), 128)?.split(Regex("\\s+"))
        val psiCpu = readPsi("cpu")
        val psiMemory = readPsi("memory")
        val psiIo = readPsi("io")
        return DiagState(
            load1 = load?.getOrNull(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            load5 = load?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            load15 = load?.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            runningTasks = load?.getOrNull(3)?.split("/")?.getOrNull(0)?.toIntOrNull()
                ?.takeIf { it >= 0 },
            totalTasks = load?.getOrNull(3)?.split("/")?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it >= 0 },
            psiSupported = psiCpu != null || psiMemory != null || psiIo != null,
            psiCpu = psiCpu,
            psiMemory = psiMemory,
            psiIo = psiIo,
        )
    }

    private fun readPsi(resource: String): PsiLine? {
        val text = readSmallText(File(File(procRoot, "pressure"), resource)) ?: return null
        // some avg10=1.23 avg60=0.45 avg300=0.12 total=12345
        val values = text.split(Regex("\\s+")).mapNotNull {
            it.split("=").takeIf { parts -> parts.size == 2 }?.let { parts -> parts[0] to parts[1] }
        }.toMap()
        if (values.isEmpty() || ("avg10" !in values && "total" !in values)) return null
        return PsiLine(
            avg10 = values["avg10"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            avg60 = values["avg60"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            avg300 = values["avg300"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            total = values["total"]?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }
}
