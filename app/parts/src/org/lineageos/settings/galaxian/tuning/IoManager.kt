/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import java.io.File
import java.io.IOException

/**
 * Per-block-device scheduler and read-ahead. Direct sysfs writes with readback,
 * session-scoped. Reboot restores stock.
 */
internal class IoManager(
    private val blockRoot: File = File("/sys/block"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private val initialSchedulers = mutableMapOf<String, String>()
    private val initialReadAhead = mutableMapOf<String, Long>()

    @Synchronized
    fun readState(): List<IoDevice> {
        val devices = blockRoot.listFiles()
            ?.filter { it.isDirectory }?.sortedBy { it.name }?.take(32)?.mapNotNull(::readDevice)
            .orEmpty()
        devices.forEach {
            initialSchedulers.putIfAbsent(it.name, it.scheduler ?: return@forEach)
            it.readAheadKb?.let { ra -> initialReadAhead.putIfAbsent(it.name, ra) }
        }
        return devices
    }

    @Synchronized
    fun setScheduler(name: String, scheduler: String): GovResult {
        val device = readDevice(deviceDir(name)) ?: return GovResult(TuningFailure.UNAVAILABLE)
        if (!device.writable) return GovResult(TuningFailure.UNAVAILABLE)
        if (scheduler !in device.available) return GovResult(TuningFailure.INVALID_VALUE)
        if (device.scheduler == scheduler) return GovResult()
        return writeVerified(deviceDir(name), "scheduler", scheduler)
    }

    @Synchronized
    fun setReadAheadKb(name: String, readAheadKb: Long): GovResult {
        if (readAheadKb !in READ_AHEAD_RANGE) return GovResult(TuningFailure.INVALID_VALUE)
        val device = readDevice(deviceDir(name)) ?: return GovResult(TuningFailure.UNAVAILABLE)
        if (!device.writable) return GovResult(TuningFailure.UNAVAILABLE)
        if (device.readAheadKb == readAheadKb) return GovResult()
        return writeVerified(deviceDir(name), "read_ahead_kb", "$readAheadKb")
    }

    @Synchronized
    fun restoreInitial(): GovResult {
        readState()
        if (initialSchedulers.isEmpty()) return GovResult(TuningFailure.UNAVAILABLE)
        var failure: TuningFailure? = null
        initialSchedulers.forEach { (name, scheduler) ->
            val result = setScheduler(name, scheduler)
            if (!result.successful && failure == null) failure = result.failure
        }
        initialReadAhead.forEach { (name, readAheadKb) ->
            val result = setReadAheadKb(name, readAheadKb)
            if (!result.successful && failure == null) failure = result.failure
        }
        return GovResult(failure)
    }

    private fun writeVerified(directory: File, node: String, value: String): GovResult = try {
        writer(File(File(directory, "queue"), node), "$value\n")
        val observed = if (node == "scheduler") parseScheduler(readSmallText(File(File(directory, "queue"), node))).first
            else readSmallText(File(File(directory, "queue"), node))
        if (observed != value) throw IoFailure(TuningFailure.READBACK_FAILED)
        GovResult()
    } catch (error: Exception) {
        GovResult((error as? IoFailure)?.reason ?: TuningFailure.WRITE_FAILED)
    }

    private fun deviceDir(name: String): File =
        File(blockRoot, TuningNames.clean(name) ?: "\u0000")

    private fun readDevice(directory: File): IoDevice? {
        if (!directory.isDirectory) return null
        val queue = File(directory, "queue")
        if (!queue.isDirectory) return null
        val (current, available) = parseScheduler(readSmallText(File(queue, "scheduler")))
        val readAheadKb = readSmallText(File(queue, "read_ahead_kb"))?.toLongOrNull()
            ?.takeIf { it in READ_AHEAD_RANGE }
        if (current == null && available.isEmpty() && readAheadKb == null) return null
        return IoDevice(
            name = directory.name,
            scheduler = current,
            available = available,
            readAheadKb = readAheadKb,
            writable = File(queue, "scheduler").canWrite() || File(queue, "read_ahead_kb").canWrite(),
        )
    }

    private class IoFailure(val reason: TuningFailure) : IOException()

    companion object {
        internal val READ_AHEAD_RANGE = 1L..65536L
        val READ_AHEAD_CHOICES = listOf(128L, 512L, 1024L, 2048L, 4096L)

        /** The active scheduler is the bracketed entry: "[mq-deadline] kyber bfq none". */
        internal fun parseScheduler(raw: String?): Pair<String?, List<String>> {
            if (raw.isNullOrBlank()) return null to emptyList()
            val parts = raw.trim().split(Regex("\\s+"))
            var current: String? = null
            val available = parts.mapNotNull { part ->
                if (part.startsWith("[") && part.endsWith("]")) {
                    TuningNames.clean(part.removePrefix("[").removeSuffix("]"))?.also { current = it }
                } else TuningNames.clean(part)
            }
            return current to available.distinct()
        }
    }
}
