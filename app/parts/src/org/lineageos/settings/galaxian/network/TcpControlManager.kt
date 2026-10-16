/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

internal enum class TcpFailure {
    UNAVAILABLE, INVALID_ALGORITHM, CONFLICT, WRITE_FAILED, READBACK_FAILED, NOT_OWNED,
}

internal data class TcpState(
    val current: String?,
    val available: List<String>,
    val initial: String?,
    val canApply: Boolean,
    val canRestore: Boolean,
    val unavailableReason: TcpFailure?,
)

internal data class TcpResult(
    val state: TcpState,
    val failure: TcpFailure? = null,
    val writeAttempted: Boolean = false,
) {
    val successful: Boolean get() = failure == null
}

/** The property request is deliberately separate from the kernel's actual value. */
internal interface TcpControlPort {
    fun readCurrent(): String
    fun readAvailable(): String
    fun controlReady(): Boolean
    fun request(algorithm: String)
}

/**
 * Changes the default for new TCP sockets. No boot persistence, module loading, or qdisc writes.
 * Call on a worker thread: init applies requests asynchronously, so success requires readback.
 */
internal class TcpControlManager(
    private val port: TcpControlPort,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var initial: String? = null
    private var owned: String? = null

    @Synchronized fun readState(): TcpState = snapshot()

    @Synchronized fun apply(target: String, expected: TcpState): TcpResult {
        val before = snapshot()
        if (target !in SUPPORTED || target !in before.available) {
            return TcpResult(before, TcpFailure.INVALID_ALGORITHM)
        }
        return applyChecked(target, expected, before)
    }

    @Synchronized fun restoreInitial(expected: TcpState): TcpResult {
        val before = snapshot()
        if (before.current != expected.current || before.available != expected.available) {
            return TcpResult(before, TcpFailure.CONFLICT)
        }
        if (!before.canRestore) return TcpResult(before, TcpFailure.NOT_OWNED)
        return applyChecked(checkNotNull(initial), expected, before)
    }

    private fun applyChecked(target: String, expected: TcpState, before: TcpState): TcpResult {
        if (!before.canApply) return TcpResult(before, TcpFailure.UNAVAILABLE)
        if (before.current != expected.current || before.available != expected.available) {
            return TcpResult(before, TcpFailure.CONFLICT)
        }
        if (before.current == target) return TcpResult(before)

        // Sysctl has no atomic compare-and-swap; recheck immediately before requesting a write.
        val guarded = snapshot()
        if (!guarded.canApply || guarded.current != before.current ||
            guarded.available != before.available) {
            return TcpResult(guarded, TcpFailure.CONFLICT)
        }
        try {
            port.request(target)
        } catch (_: Exception) {
            return TcpResult(snapshot(), TcpFailure.WRITE_FAILED, writeAttempted = true)
        }

        var previousMatched = false
        repeat(26) { attempt ->
            val observed = snapshot()
            if (!observed.canApply) {
                return TcpResult(observed, TcpFailure.READBACK_FAILED, writeAttempted = true)
            }
            if (observed.available != before.available ||
                observed.current !in listOf(before.current, target)) {
                owned = null
                return TcpResult(observed.copy(canRestore = false), TcpFailure.CONFLICT, true)
            }
            if (observed.current == target) {
                if (previousMatched) {
                    owned = target.takeUnless { it == initial }
                    return TcpResult(observed.copy(canRestore = canRestore(observed)),
                        writeAttempted = true)
                }
                previousMatched = true
            } else {
                // A target observed and then replaced belongs to another controller.
                if (previousMatched) {
                    owned = null
                    return TcpResult(observed.copy(canRestore = false), TcpFailure.CONFLICT, true)
                }
            }
            if (attempt < 25) {
                try {
                    pause(40)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return TcpResult(snapshot(), TcpFailure.READBACK_FAILED, true)
                }
            }
        }
        // Never roll back a timeout: a late init action or an external writer may still own it.
        return TcpResult(snapshot(), TcpFailure.READBACK_FAILED, writeAttempted = true)
    }

    private fun snapshot(): TcpState {
        val current = runCatching { port.readCurrent().trim() }
            .getOrNull()?.takeIf { NAME.matches(it) }
        val rawAvailable = runCatching { port.readAvailable().trim() }.getOrNull()
        val available = rawAvailable?.takeIf { it.length <= 1024 }
            ?.split(Regex("\\s+"))?.takeIf { values ->
                values.isNotEmpty() && values.all { NAME.matches(it) }
            }?.toSet()?.let { values -> SUPPORTED.filter { it in values } }.orEmpty()
        val ready = runCatching { port.controlReady() }.getOrDefault(false)
        if (initial == null && current != null) initial = current
        if (owned != null && current != owned) owned = null
        val applicable = current != null && available.isNotEmpty() && ready
        val state = TcpState(current, available, initial, applicable, false,
            if (applicable) null else TcpFailure.UNAVAILABLE)
        return state.copy(canRestore = canRestore(state))
    }

    private fun canRestore(state: TcpState): Boolean = state.canApply && owned != null &&
        owned == state.current && initial in state.available && initial != state.current

    companion object {
        // Must match the fixed-value init actions. Unknown algorithms remain read-only.
        val SUPPORTED = listOf("reno", "cubic", "bic", "westwood", "htcp", "bbr", "bbr3")
        private val NAME = Regex("[a-zA-Z0-9_-]{1,15}")
    }
}
