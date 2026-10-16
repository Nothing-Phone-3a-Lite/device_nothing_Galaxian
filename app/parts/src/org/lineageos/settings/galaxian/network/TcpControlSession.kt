/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.network

import android.os.SystemProperties
import java.io.File

/** Keep the initial value and ownership across recreation of the network screen. */
internal object TcpControlSession {
    val manager = TcpControlManager(object : TcpControlPort {
        override fun readCurrent() = File("$SYSCTL/tcp_congestion_control").readText()
        override fun readAvailable() = File("$SYSCTL/tcp_available_congestion_control").readText()
        override fun controlReady() = SystemProperties.get("sys.galaxianparts.tcp_ready") == "1"
        override fun request(algorithm: String) {
            SystemProperties.set("sys.galaxianparts.tcp_congestion", algorithm)
        }
    })

    private const val SYSCTL = "/proc/sys/net/ipv4"
}
