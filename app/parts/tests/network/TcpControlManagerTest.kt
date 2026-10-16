/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

private class Port : TcpControlPort {
    var current = "cubic"
    var available = "reno cubic westwood bbr bbr3 custom"
    var ready = true
    var readable = true
    val writes = mutableListOf<String>()
    var action: (String) -> Unit = { current = it }
    override fun readCurrent(): String = if (readable) current else throw IOException("missing")
    override fun readAvailable() = available
    override fun controlReady() = ready
    override fun request(algorithm: String) {
        writes += algorithm
        action(algorithm)
    }
}

private fun checkFailure(result: TcpResult, failure: TcpFailure) {
    check(result.failure == failure) { "Expected $failure, got $result" }
    check(!result.successful)
}

fun main() {
    var cases = 0
    fun test(name: String, block: () -> Unit) {
        block()
        cases++
        println("PASS: $name")
    }

    test("runtime intersection excludes unsupported algorithms and preserves unknown current") {
        val port = Port().apply { current = "custom" }
        val manager = TcpControlManager(port, pause = {})
        val state = manager.readState()
        check(state.current == "custom" && state.initial == "custom")
        check(state.available == listOf("reno", "cubic", "westwood", "bbr", "bbr3"))
        check(state.canApply && !state.canRestore)
        check(manager.apply("bbr3", state).successful)
        check(!manager.readState().canRestore)
    }
    test("reject unknown, injection, empty, whitespace and runtime-absent selections") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        val state = manager.readState()
        listOf("custom", "bbr3\nreno", "cubic; reboot", "", " bbr3", "htcp").forEach {
            checkFailure(manager.apply(it, state), TcpFailure.INVALID_ALGORITHM)
        }
        check(port.writes.isEmpty())
    }
    test("missing reader or init integration is unavailable without losing observed initial") {
        val port = Port().apply { ready = false }
        val manager = TcpControlManager(port, pause = {})
        val state = manager.readState()
        check(state.current == "cubic" && !state.canApply)
        checkFailure(manager.apply("bbr3", state), TcpFailure.UNAVAILABLE)
        port.ready = true
        port.readable = false
        check(manager.readState().current == null && manager.readState().initial == "cubic")
        check(port.writes.isEmpty())
    }
    test("malformed or empty runtime catalogue disables controls") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        listOf("", "cubic\nbbr3;bad", "bbr3 "+"x".repeat(1024)).forEach {
            port.available = it
            check(!manager.readState().canApply)
        }
    }
    test("successful readback enables explicit restore of process initial") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        val state = manager.readState()
        check(manager.apply("bbr3", state).successful)
        check(manager.readState().canRestore)
        check(manager.apply("westwood", manager.readState()).successful)
        check(manager.readState().initial == "cubic")
        check(manager.restoreInitial(manager.readState()).successful)
        check(port.current == "cubic" && !manager.readState().canRestore)
        check(port.writes == listOf("bbr3", "westwood", "cubic"))
    }
    test("selecting already active value is a no-op and does not claim ownership") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        check(manager.apply("cubic", manager.readState()).successful)
        check(port.writes.isEmpty() && !manager.readState().canRestore)
        checkFailure(manager.restoreInitial(manager.readState()), TcpFailure.NOT_OWNED)
    }
    test("stale UI does not overwrite an externally changed value") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        val stale = manager.readState()
        port.current = "westwood"
        checkFailure(manager.apply("bbr3", stale), TcpFailure.CONFLICT)
        check(port.writes.isEmpty() && port.current == "westwood")
    }
    test("runtime catalogue changes invalidate a queued selection") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        val stale = manager.readState()
        port.available += " htcp"
        checkFailure(manager.apply("bbr3", stale), TcpFailure.CONFLICT)
        check(port.writes.isEmpty())
    }
    test("property rejection is reported without claiming success") {
        val port = Port().apply { action = { throw SecurityException("denied") } }
        val manager = TcpControlManager(port, pause = {})
        val result = manager.apply("bbr3", manager.readState())
        checkFailure(result, TcpFailure.WRITE_FAILED)
        check(result.writeAttempted && result.state.current == "cubic")
    }
    test("accepted property without kernel readback times out after one second") {
        val port = Port().apply { action = {} }
        var waited = 0L
        val manager = TcpControlManager(port, pause = { waited += it })
        val result = manager.apply("bbr3", manager.readState())
        checkFailure(result, TcpFailure.READBACK_FAILED)
        check(waited == 1000L && port.writes == listOf("bbr3"))
        check(result.state.current == "cubic" && !result.state.canRestore)
    }
    test("delayed init succeeds only after actual stable readback") {
        val port = Port().apply { action = {} }
        var pauses = 0
        val manager = TcpControlManager(port, pause = {
            if (++pauses == 3) port.current = "bbr3"
        })
        check(manager.apply("bbr3", manager.readState()).successful)
        check(pauses == 4 && manager.readState().canRestore)
    }
    test("concurrent external change during verification is never rolled back") {
        val port = Port()
        val manager = TcpControlManager(port, pause = { port.current = "westwood" })
        val result = manager.apply("bbr3", manager.readState())
        checkFailure(result, TcpFailure.CONFLICT)
        check(port.current == "westwood" && port.writes == listOf("bbr3"))
        check(!manager.readState().canRestore)
    }
    test("replacement with original value also cancels ownership") {
        val port = Port()
        val manager = TcpControlManager(port, pause = { port.current = "cubic" })
        checkFailure(manager.apply("bbr3", manager.readState()), TcpFailure.CONFLICT)
        check(!manager.readState().canRestore)
    }
    test("external update after success blocks restore even from a refreshed screen") {
        val port = Port()
        val manager = TcpControlManager(port, pause = {})
        check(manager.apply("bbr3", manager.readState()).successful)
        val stale = manager.readState()
        port.current = "westwood"
        checkFailure(manager.restoreInitial(stale), TcpFailure.CONFLICT)
        checkFailure(manager.restoreInitial(manager.readState()), TcpFailure.NOT_OWNED)
        check(port.current == "westwood" && port.writes == listOf("bbr3"))
    }
    test("reading does not write or restore after controller recreation") {
        val port = Port()
        val first = TcpControlManager(port, pause = {})
        check(first.apply("bbr3", first.readState()).successful)
        val recreated = TcpControlManager(port, pause = {})
        check(recreated.readState().initial == "bbr3" && !recreated.readState().canRestore)
        check(port.writes == listOf("bbr3"))
    }
    test("concurrent callers serialize and the stale second caller cannot overwrite") {
        val port = Port()
        val pending = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val manager = TcpControlManager(port, pause = {
            pending.countDown()
            proceed.await()
        })
        val before = manager.readState()
        var first: TcpResult? = null
        var second: TcpResult? = null
        val one = thread { first = manager.apply("bbr3", before) }
        pending.await()
        val two = thread { second = manager.apply("westwood", before) }
        proceed.countDown()
        one.join()
        two.join()
        check(first?.successful == true)
        checkFailure(checkNotNull(second), TcpFailure.CONFLICT)
        check(port.writes == listOf("bbr3"))
    }
    println("$cases TCP control tests passed")
}
