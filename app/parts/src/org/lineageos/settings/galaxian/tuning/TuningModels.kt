/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import java.io.File
import org.json.JSONObject

/** Shared failure for tuning controls. Missing kernel nodes are never failures to hide. */
internal enum class TuningFailure {
    UNAVAILABLE, NOT_SUPPORTED, INVALID_VALUE, CONFLICT, WRITE_FAILED, READBACK_FAILED,
}

/** Read a small sysfs/proc file. Denied or missing nodes return null: the kernel lacks it. */
internal fun readSmallText(file: File, maxChars: Int = 512): String? = runCatching {
    file.bufferedReader().use { it.readText().take(maxChars).trim().takeIf { s -> s.isNotEmpty() } }
}.getOrNull()

internal fun readSmallLong(file: File): Long? = readSmallText(file)?.toLongOrNull()
internal fun readSmallInt(file: File): Int? = readSmallText(file)?.toIntOrNull()

internal object TuningNames {
    val PLAIN = Regex("[a-zA-Z0-9_.\\-]{1,64}")
    fun clean(value: String?): String? = value?.trim()?.takeIf { PLAIN.matches(it) }
}

/* CPU governor and schedutil. */

internal data class CpuGovPolicy(
    val id: Int,
    val governor: String?,
    val available: List<String>,
    val writable: Boolean,
    val rateLimitUs: Long?,
    val rateWritable: Boolean,
)

internal data class CpuGovState(
    val policies: List<CpuGovPolicy> = emptyList(),
    val schedutilPresent: Boolean = false,
)

/* GPU governor across devfreq-style devices. Empty means the kernel exposes none. */

internal data class GpuGovDevice(
    val name: String,
    val governor: String?,
    val available: List<String>,
    val writable: Boolean,
)

internal data class GpuGovState(val devices: List<GpuGovDevice> = emptyList())

/* Block I/O. */

internal data class IoDevice(
    val name: String,
    val scheduler: String?,
    val available: List<String>,
    val readAheadKb: Long?,
    val writable: Boolean,
)

/* VM presets: curated fixed values applied through init, verified by readback.
 * swappiness is excluded: the MediaTek vendor policy labels that node
 * (proc_swappiness) and only vendor_init may write it. */

internal enum class VmPreset(
    val dirtyRatio: Int,
    val dirtyBackground: Int,
    val cachePressure: Int,
) {
    STOCK(20, 10, 100),
    BALANCED(15, 5, 80),
    PERFORMANCE(20, 10, 50),
    BATTERY(10, 5, 150),
}

internal data class VmState(
    val dirtyRatio: Int? = null,
    val dirtyBackground: Int? = null,
    val cachePressure: Int? = null,
    val activePreset: VmPreset? = null,
    val canApply: Boolean = false,
)

/* uclamp presets. Values are the 0..1024 utilization clamp range. */

internal enum class UclampPreset(val min: Int, val max: Int) {
    DEFAULT(0, 1024),
    BATTERY(0, 512),
    PERFORMANCE(128, 1024),
}

internal data class UclampState(
    val min: Int? = null,
    val max: Int? = null,
    val activePreset: UclampPreset? = null,
    val canApply: Boolean = false,
)

/* BORE and LMK: probed, reported honestly, tuned only where the kernel allows. */

internal data class BoreState(
    val supported: Boolean = false,
    val detail: String? = null,
)

internal data class LmkState(
    val driverPresent: Boolean = false,
    val props: Map<String, String> = emptyMap(),
)

/* Diagnostics: load average and PSI stalls, read-only. */

internal data class PsiLine(
    val avg10: Double? = null,
    val avg60: Double? = null,
    val avg300: Double? = null,
    val total: Long? = null,
)

internal data class DiagState(
    val load1: Double? = null,
    val load5: Double? = null,
    val load15: Double? = null,
    val runningTasks: Int? = null,
    val totalTasks: Int? = null,
    val psiSupported: Boolean = false,
    val psiCpu: PsiLine? = null,
    val psiMemory: PsiLine? = null,
    val psiIo: PsiLine? = null,
)

/* Profile export/import, versioned JSON. Unknown sections are ignored, known ones validated. */

internal data class CpuProfileEntry(val min: Long?, val max: Long?, val governor: String?)
internal data class GpuProfileEntry(val min: Long?, val max: Long?)
internal data class IoProfileEntry(val scheduler: String?, val readAheadKb: Long?)

internal data class TuningProfile(
    val cpu: Map<String, CpuProfileEntry> = emptyMap(),
    val gpu: GpuProfileEntry? = null,
    val tcp: String? = null,
    val vm: String? = null,
    val uclamp: String? = null,
    val io: Map<String, IoProfileEntry> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        if (cpu.isNotEmpty()) put("cpu", JSONObject().apply {
            cpu.forEach { (id, entry) -> put(id, JSONObject().apply {
                entry.min?.let { put("min", it) }
                entry.max?.let { put("max", it) }
                entry.governor?.let { put("governor", it) }
            }) }
        })
        gpu?.let { put("gpu", JSONObject().apply {
            it.min?.let { v -> put("min", v) }
            it.max?.let { v -> put("max", v) }
        }) }
        tcp?.let { put("tcp", it) }
        vm?.let { put("vm", it) }
        uclamp?.let { put("uclamp", it) }
        if (io.isNotEmpty()) put("io", JSONObject().apply {
            io.forEach { (dev, entry) -> put(dev, JSONObject().apply {
                entry.scheduler?.let { put("scheduler", it) }
                entry.readAheadKb?.let { put("read_ahead_kb", it) }
            }) }
        })
    }

    companion object {
        fun parse(raw: String): TuningProfile? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optInt("version", -1) != 1) return null
            fun cpuEntry(o: JSONObject) = CpuProfileEntry(
                min = o.takeIf { it.has("min") }?.optLong("min", -1)?.takeIf { it > 0 },
                max = o.takeIf { it.has("max") }?.optLong("max", -1)?.takeIf { it > 0 },
                governor = o.takeIf { it.has("governor") }?.optString("governor")
                    ?.takeIf { TuningNames.PLAIN.matches(it) },
            )
            val cpu = mutableMapOf<String, CpuProfileEntry>()
            root.optJSONObject("cpu")?.let { obj ->
                obj.keys().forEach { id ->
                    if (id.toIntOrNull() == null) return null
                    (obj.optJSONObject(id) ?: return null).let { cpu[id] = cpuEntry(it) }
                }
            }
            val gpu = root.optJSONObject("gpu")?.let {
                GpuProfileEntry(
                    it.takeIf { o -> o.has("min") }?.optLong("min", -1)?.takeIf { v -> v > 0 },
                    it.takeIf { o -> o.has("max") }?.optLong("max", -1)?.takeIf { v -> v > 0 },
                )
            }
            val io = mutableMapOf<String, IoProfileEntry>()
            root.optJSONObject("io")?.let { obj ->
                obj.keys().forEach { dev ->
                    if (TuningNames.clean(dev) == null) return null
                    (obj.optJSONObject(dev) ?: return null).let { o -> io[dev] = IoProfileEntry(
                        scheduler = o.takeIf { it.has("scheduler") }?.optString("scheduler")
                            ?.takeIf { TuningNames.PLAIN.matches(it) },
                        readAheadKb = o.takeIf { it.has("read_ahead_kb") }?.optLong("read_ahead_kb", -1)
                            ?.takeIf { it in 1..65536 },
                    ) }
                }
            }
            fun known(value: String?, valid: Set<String>): String? =
                value?.takeIf { TuningNames.PLAIN.matches(it) && it in valid }
            return TuningProfile(
                cpu = cpu,
                gpu = gpu,
                tcp = known(root.takeIf { it.has("tcp") }?.optString("tcp"), TCP_ALGORITHMS),
                vm = known(root.takeIf { it.has("vm") }?.optString("vm"), VM_NAMES),
                uclamp = known(root.takeIf { it.has("uclamp") }?.optString("uclamp"), UCLAMP_NAMES),
                io = io,
            )
        }

        private val TCP_ALGORITHMS = setOf("reno", "cubic", "bic", "westwood", "htcp", "bbr", "bbr3")
        private val VM_NAMES = VmPreset.entries.map { it.name.lowercase() }.toSet()
        private val UCLAMP_NAMES = UclampPreset.entries.map { it.name.lowercase() }.toSet()
    }
}
