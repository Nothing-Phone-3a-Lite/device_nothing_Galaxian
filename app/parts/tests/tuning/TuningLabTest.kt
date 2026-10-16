/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.tuning

import java.io.File
import java.nio.file.Files

private class FakeVmPort(var ready: Boolean = true) : VmPort {
    var requested: VmPreset? = null
    override fun controlReady() = ready
    override fun requestPreset(preset: VmPreset) {
        requested = preset
    }
}

private class FakeUclampPort(var ready: Boolean = true) : UclampPort {
    var requested: UclampPreset? = null
    override fun controlReady() = ready
    override fun requestPreset(preset: UclampPreset) {
        requested = preset
    }
}

fun main() {
    check(IoManager.parseScheduler("[mq-deadline] kyber bfq none") ==
        ("mq-deadline" to listOf("mq-deadline", "kyber", "bfq", "none")))
    check(IoManager.parseScheduler("none") == ("none" to listOf("none")))
    check(IoManager.parseScheduler(null) == (null to emptyList()))
    check(IoManager.parseScheduler("") == (null to emptyList()))
    println("PASS scheduler bracket parsing")

    check(VmPreset.STOCK.swappiness == 60 && VmPreset.PERFORMANCE.cachePressure == 50 &&
        VmPreset.BATTERY.swappiness == 100 && VmPreset.BALANCED.dirtyBackground == 5)
    check(UclampPreset.DEFAULT.min == 0 && UclampPreset.DEFAULT.max == 1024 &&
        UclampPreset.BATTERY.max == 512 && UclampPreset.PERFORMANCE.min == 128)
    check(CpuGovManager.RATE_RANGE.first == 100L && IoManager.READ_AHEAD_CHOICES.contains(512L))
    println("PASS curated preset values")

    val root = Files.createTempDirectory("tuning-test").toFile()
    try {
        // CPU governor tree.
        val policy0 = File(root, "cpu/cpufreq/policy0").apply { mkdirs() }
        File(policy0, "scaling_governor").writeText("schedutil\n")
        File(policy0, "scaling_available_governors").writeText("schedutil performance\n")
        File(policy0, "scaling_governor").setWritable(true)
        val gov = CpuGovManager(File(root, "cpu"))
        val govState = gov.readState()
        check(govState.policies.size == 1 && govState.policies[0].governor == "schedutil")
        check(!govState.schedutilPresent)
        check(gov.setGovernor(0, "nope").failure == TuningFailure.INVALID_VALUE)
        check(gov.setGovernor(9, "schedutil").failure == TuningFailure.UNAVAILABLE)
        File(root, "cpu/cpufreq/schedutil").mkdirs()
        File(root, "cpu/cpufreq/schedutil/rate_limit_us").writeText("1000\n")
        check(gov.readState().schedutilPresent)
        check(gov.setRateLimitUs(50).failure == TuningFailure.INVALID_VALUE)
        println("PASS CPU governor discovery and validation")

        // Block tree.
        val queue = File(root, "block/sda/queue").apply { mkdirs() }
        File(queue, "scheduler").writeText("[mq-deadline] none\n")
        File(queue, "read_ahead_kb").writeText("128\n")
        val io = IoManager(File(root, "block"))
        val devices = io.readState()
        check(devices.size == 1 && devices[0].scheduler == "mq-deadline")
        check(devices[0].available == listOf("mq-deadline", "none"))
        check(devices[0].readAheadKb == 128L)
        check(io.setScheduler("sda", "bogus").failure == TuningFailure.INVALID_VALUE)
        check(io.setReadAheadKb("sda", 0).failure == TuningFailure.INVALID_VALUE)
        println("PASS block discovery and validation")

        // VM preset round-trip through a fake port that behaves like init.
        val vmRoot = File(root, "vm").apply { mkdirs() }
        val vmFiles = mapOf("swappiness" to "60", "dirty_ratio" to "20",
            "dirty_background_ratio" to "10", "vfs_cache_pressure" to "100")
        vmFiles.forEach { (name, value) -> File(vmRoot, name).writeText("$value\n") }
        val port = FakeVmPort()
        val vm = VmManager(port, vmRoot, pause = {})
        val before = vm.readState()
        check(before.activePreset == VmPreset.STOCK && before.canApply)
        port.ready = false
        check(vm.applyPreset(VmPreset.BALANCED, before).failure == TuningFailure.UNAVAILABLE)
        port.ready = true
        val requested = vm.applyPreset(VmPreset.BALANCED, before)
        check(!requested.successful && requested.failure == TuningFailure.READBACK_FAILED)
        check(port.requested == VmPreset.BALANCED)
        // Simulate init having applied it.
        File(vmRoot, "swappiness").writeText("60\n")
        File(vmRoot, "dirty_ratio").writeText("15\n")
        File(vmRoot, "dirty_background_ratio").writeText("5\n")
        File(vmRoot, "vfs_cache_pressure").writeText("80\n")
        val applied = vm.applyPreset(VmPreset.BALANCED, before)
        check(applied.successful && applied.state.activePreset == VmPreset.BALANCED)
        println("PASS VM preset request and readback")

        // uclamp conflict detection.
        val kernRoot = File(root, "kernel").apply { mkdirs() }
        File(kernRoot, "sched_uclamp_util_min").writeText("0\n")
        File(kernRoot, "sched_uclamp_util_max").writeText("1024\n")
        val uclampPort = FakeUclampPort()
        val uclamp = UclampManager(uclampPort, kernRoot, pause = {})
        val uBefore = uclamp.readState()
        check(uBefore.activePreset == UclampPreset.DEFAULT && uBefore.canApply)
        val stale = uBefore.copy(min = 64)
        check(uclamp.applyPreset(UclampPreset.BATTERY, stale).failure == TuningFailure.CONFLICT)
        println("PASS uclamp conflict detection")

        // Profile JSON round-trip and strict rejection.
        val profile = TuningProfile(
            cpu = mapOf("0" to CpuProfileEntry(500000L, 2000000L, "schedutil")),
            gpu = GpuProfileEntry(300000L, 800000L),
            tcp = "bbr",
            vm = "balanced",
            uclamp = "default",
            io = mapOf("sda" to IoProfileEntry("mq-deadline", 512L)),
        )
        val parsed = TuningProfile.parse(profile.toJson().toString())
        check(parsed == profile)
        check(TuningProfile.parse("{}") == null)
        check(TuningProfile.parse("{\"version\":2}") == null)
        check(TuningProfile.parse("{\"version\":1,\"tcp\":\"nope\"}")?.tcp == null)
        check(TuningProfile.parse("{\"version\":1,\"cpu\":{\"x\":{}}}") == null)
        println("PASS profile JSON round-trip and rejection")

        // Diagnostics parsing.
        val procRoot = File(root, "proc").apply { mkdirs() }
        File(procRoot, "loadavg").writeText("1.50 0.75 0.25 3/512 12345\n")
        val pressure = File(procRoot, "pressure").apply { mkdirs() }
        File(pressure, "cpu").writeText("some avg10=1.23 avg60=0.45 avg300=0.12 total=999\n")
        val diag = DiagManager(procRoot).readState()
        check(diag.load1 == 1.5 && diag.load5 == 0.75 && diag.load15 == 0.25)
        check(diag.runningTasks == 3 && diag.totalTasks == 512)
        check(diag.psiSupported && diag.psiCpu?.avg10 == 1.23 && diag.psiCpu?.total == 999L)
        check(diag.psiMemory == null)
        println("PASS load and PSI parsing")

        // BORE absent and LMK driver gone on this layout.
        check(!BoreManager(procRoot, procRoot).readState().supported)
        val lmk = LmkManager(procRoot).readState()
        check(!lmk.driverPresent)
        println("PASS unsupported knobs stay unsupported")
    } finally {
        root.deleteRecursively()
    }
    println("7 tuning checks passed")
}
