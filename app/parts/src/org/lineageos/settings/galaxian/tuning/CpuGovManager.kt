/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import java.io.File
import java.io.IOException

internal data class GovResult(val failure: TuningFailure? = null) {
    val successful: Boolean get() = failure == null
}

/**
 * Per-policy governor plus the shared schedutil rate limit. Direct sysfs writes with
 * readback, session-scoped like the CPU frequency controls. Reboot restores stock.
 */
internal class CpuGovManager(
    private val cpuRoot: File = File("/sys/devices/system/cpu"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private val initialGovernors = mutableMapOf<Int, String>()
    private var initialRateUs: Long? = null
    private var rateSeen = false

    @Synchronized
    fun readState(): CpuGovState {
        val policies = POLICY_IDS.mapNotNull(::readPolicy)
        policies.forEach { initialGovernors.putIfAbsent(it.id, it.governor ?: return@forEach) }
        readRateUs()?.let {
            if (!rateSeen) {
                initialRateUs = it
                rateSeen = true
            }
        }
        return CpuGovState(policies, schedutilPresent = File(cpuRoot, "cpufreq/schedutil").isDirectory)
    }

    @Synchronized
    fun setGovernor(id: Int, governor: String): GovResult {
        val policy = readPolicy(id) ?: return GovResult(TuningFailure.UNAVAILABLE)
        if (!policy.writable) return GovResult(TuningFailure.UNAVAILABLE)
        if (governor !in policy.available) return GovResult(TuningFailure.INVALID_VALUE)
        if (policy.governor == governor) return GovResult()
        return try {
            writer(File(policyDir(id), "scaling_governor"), "$governor\n")
            if (readPolicy(id)?.governor != governor) throw GovFailure(TuningFailure.READBACK_FAILED)
            GovResult()
        } catch (error: Exception) {
            GovResult((error as? GovFailure)?.reason ?: TuningFailure.WRITE_FAILED)
        }
    }

    @Synchronized
    fun setRateLimitUs(valueUs: Long): GovResult {
        if (valueUs !in RATE_RANGE) return GovResult(TuningFailure.INVALID_VALUE)
        val file = rateFile() ?: return GovResult(TuningFailure.NOT_SUPPORTED)
        if (!file.canWrite()) return GovResult(TuningFailure.UNAVAILABLE)
        return try {
            writer(file, "$valueUs\n")
            if (readRateUs() != valueUs) throw GovFailure(TuningFailure.READBACK_FAILED)
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
        initialGovernors.forEach { (id, governor) ->
            val result = setGovernor(id, governor)
            if (!result.successful && failure == null) failure = result.failure
        }
        val rate = initialRateUs
        if (rate != null && readRateUs() != null && readRateUs() != rate) {
            val result = setRateLimitUs(rate)
            if (!result.successful && failure == null) failure = result.failure
        }
        return GovResult(failure)
    }

    private fun readPolicy(id: Int): CpuGovPolicy? {
        if (id !in POLICY_IDS) return null
        val directory = policyDir(id)
        val governor = readSmallText(File(directory, "scaling_governor"))?.let(TuningNames::clean)
        val available = readSmallText(File(directory, "scaling_available_governors"))
            ?.split(Regex("\\s+"))?.mapNotNull(TuningNames::clean)
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (governor == null && available.isEmpty()) return null
        return CpuGovPolicy(
            id = id,
            governor = governor,
            available = available,
            writable = available.isNotEmpty() && File(directory, "scaling_governor").canWrite(),
            rateLimitUs = readRateUs(),
            rateWritable = rateFile()?.canWrite() == true,
        )
    }

    private fun rateFile(): File? = File(cpuRoot, "cpufreq/schedutil/rate_limit_us")
        .takeIf { it.isFile || it.canWrite() || readSmallLong(it) != null }

    private fun readRateUs(): Long? = rateFile()?.let(::readSmallLong)
        ?.takeIf { it in RATE_RANGE }

    private fun policyDir(id: Int) = File(cpuRoot, "cpufreq/policy$id")

    private class GovFailure(val reason: TuningFailure) : IOException()

    companion object {
        private val POLICY_IDS = listOf(0, 4)
        internal val RATE_RANGE = 100L..100_000L
    }
}
