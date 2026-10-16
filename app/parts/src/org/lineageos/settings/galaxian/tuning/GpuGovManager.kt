/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import java.io.File
import java.io.IOException

/**
 * GPU governor across devfreq-style devices. Galaxian's MediaTek GPUFREQ interface has
 * no governor node, so this normally reports no devices and the UI says the kernel
 * does not support it. Nothing is synthesized when the nodes are absent.
 */
internal class GpuGovManager(
    private val devfreqRoot: File = File("/sys/class/devfreq"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private val initialGovernors = mutableMapOf<String, String>()

    @Synchronized
    fun readState(): GpuGovState {
        val devices = devfreqRoot.listFiles()
            ?.filter { it.isDirectory && it.name != "." && it.name != ".." }
            ?.sortedBy { it.name }?.take(16)?.mapNotNull(::readDevice).orEmpty()
        devices.forEach { initialGovernors.putIfAbsent(it.name, it.governor ?: return@forEach) }
        return GpuGovState(devices)
    }

    @Synchronized
    fun setGovernor(name: String, governor: String): GovResult {
        val clean = TuningNames.clean(name) ?: return GovResult(TuningFailure.INVALID_VALUE)
        val device = readDevice(File(devfreqRoot, clean)) ?: return GovResult(TuningFailure.UNAVAILABLE)
        if (!device.writable) return GovResult(TuningFailure.UNAVAILABLE)
        if (governor !in device.available) return GovResult(TuningFailure.INVALID_VALUE)
        if (device.governor == governor) return GovResult()
        return try {
            writer(File(File(devfreqRoot, clean), "governor"), "$governor\n")
            if (readDevice(File(devfreqRoot, clean))?.governor != governor) {
                throw GovFailure(TuningFailure.READBACK_FAILED)
            }
            GovResult()
        } catch (error: Exception) {
            GovResult((error as? GovFailure)?.reason ?: TuningFailure.WRITE_FAILED)
        }
    }

    @Synchronized
    fun restoreInitial(): GovResult {
        readState()
        if (initialGovernors.isEmpty()) return GovResult(TuningFailure.UNAVAILABLE)
        var failure: TuningFailure? = null
        initialGovernors.forEach { (name, governor) ->
            val result = setGovernor(name, governor)
            if (!result.successful && failure == null) failure = result.failure
        }
        return GovResult(failure)
    }

    private fun readDevice(directory: File): GpuGovDevice? {
        val governor = readSmallText(File(directory, "governor"))?.let(TuningNames::clean)
        val available = readSmallText(File(directory, "available_governors"))
            ?.split(Regex("\\s+"))?.mapNotNull(TuningNames::clean)
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (governor == null && available.isEmpty()) return null
        return GpuGovDevice(
            name = directory.name,
            governor = governor,
            available = available,
            writable = available.isNotEmpty() && File(directory, "governor").canWrite(),
        )
    }

    private class GovFailure(val reason: TuningFailure) : IOException()
}
