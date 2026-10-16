/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.galaxian.network

import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager

fun main() {
    check(NetworkValues.wifiRssi(-55) == -55)
    check(NetworkValues.wifiRssi(-126) == -126)
    for (value in listOf(-127, 0, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
        check(NetworkValues.wifiRssi(value) == null)
    }
    check(NetworkValues.positive(-1) == null && NetworkValues.positive(Int.MAX_VALUE) == null)
    check(NetworkValues.positive(1200) == 1200)
    println("PASS Wi-Fi invalid RSSI/link speed values remain unavailable")

    check(cellularSignalReading(CellSignalStrengthLte()) == null)
    check(cellularSignalReading(CellSignalStrengthNr()) == null)
    println("PASS absent modem technologies do not appear as fake zero-dBm signals")

    val lte = cellularSignalReading(CellSignalStrengthLte(-75, -95, -12, 20,
        CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE))!!
    check(lte.technology == "LTE" && lte.dbm == -95 && lte.rsrpDbm == -95)
    check(lte.rsrqDb == -12 && lte.sinrDb == 20)
    check(lte.level in 0..4)
    val zeroSinr = cellularSignalReading(CellSignalStrengthLte(-95, -120, -25, 0,
        CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE))!!
    check(zeroSinr.sinrDb == 0)
    println("PASS LTE RSRP/RSRQ/SINR units and valid zero dB are preserved")

    val nr = cellularSignalReading(CellSignalStrengthNr(-100, -10, 10, -85, -12, 25))!!
    check(nr.technology == "NR" && nr.dbm == -85 && nr.rsrpDbm == -85)
    check(nr.rsrqDb == -12 && nr.sinrDb == 25)
    val mixed = listOf(lte, nr)
    check(mixed.map { it.technology } == listOf("LTE", "NR"))
    println("PASS NR uses SS metrics and remains distinct from LTE in NSA measurements")

    check(networkTypeLabel(TelephonyManager.NETWORK_TYPE_UNKNOWN) == null)
    check(networkTypeLabel(999) == null)
    check(networkTypeLabel(TelephonyManager.NETWORK_TYPE_LTE) == "LTE")
    check(networkTypeLabel(TelephonyManager.NETWORK_TYPE_NR) == "NR")
    check(NetworkSnapshot().mobile.sims.isEmpty())
    check(NetworkSnapshot().wifi.rssiDbm == null)
    println("PASS unknown technologies and initial state never fabricate measurements")

    val emergency = ServiceState().apply {
        setStateOutOfService()
        setEmergencyOnly(true)
    }
    val emergencySnapshot = SimSnapshot(0).withServiceState(emergency)
    check(emergencySnapshot.serviceState == MobileServiceState.EMERGENCY_ONLY)
    val poweredOff = ServiceState().apply { setStateOff(); setEmergencyOnly(true) }
    val offSnapshot = SimSnapshot(0, dataNetworkType = "LTE", displayNetworkType = "5G NSA",
        signals = mixed).withServiceState(poweredOff)
    check(offSnapshot.serviceState == MobileServiceState.POWER_OFF)
    check(offSnapshot.signals.isEmpty() && offSnapshot.dataNetworkType == null &&
        offSnapshot.displayNetworkType == null)
    check(offSnapshot.withRadioTelemetry { it.copy(signals = mixed) } == offSnapshot)
    check(offSnapshot.withRadioTelemetry { it.copy(dataNetworkType = "LTE") } == offSnapshot)
    check(offSnapshot.withRadioTelemetry { it.copy(displayNetworkType = "5G NSA") } == offSnapshot)
    val inService = ServiceState().apply { setState(ServiceState.STATE_IN_SERVICE); setEmergencyOnly(true) }
    val resumed = offSnapshot.withServiceState(inService).withRadioTelemetry {
        it.copy(signals = mixed, dataNetworkType = "LTE")
    }
    check(resumed.serviceState == MobileServiceState.IN_SERVICE && resumed.signals == mixed)
    println("PASS emergency-only uses its flag; power-off rejects queued telemetry until service resumes")

}
