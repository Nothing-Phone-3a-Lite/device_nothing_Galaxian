/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import org.lineageos.settings.galaxian.ui.PartsGroupShape
import org.lineageos.settings.galaxian.ui.PartsPageGutter
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold

@Composable
fun PartsHomeScreen(onNavigate: (String) -> Unit) {
    PartsScaffold(title = stringResource(R.string.parts_title)) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item { DeviceHero() }
            item { SpecGrid() }
            item {
                PartsPreferenceGroup(title = stringResource(R.string.parts_performance)) {
                    PartsPreference(
                        title = stringResource(R.string.parts_cpu_title),
                        summary = stringResource(R.string.parts_cpu_summary),
                        icon = Icons.Filled.Speed,
                        onClick = { onNavigate("cpu") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.gpu_settings_title),
                        summary = stringResource(R.string.gpu_home_summary),
                        icon = Icons.Filled.DeveloperBoard,
                        onClick = { onNavigate("gpu") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_adaptive_title),
                        summary = stringResource(R.string.parts_adaptive_summary),
                        icon = Icons.Filled.AutoAwesome,
                        onClick = { onNavigate("adaptive") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_memory_title),
                        summary = stringResource(R.string.parts_memory_summary),
                        icon = Icons.Filled.Memory,
                        onClick = { onNavigate("memory") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.tuning_title),
                        summary = stringResource(R.string.tuning_home_summary),
                        icon = Icons.Filled.Tune,
                        onClick = { onNavigate("tuning") },
                    )
                }
            }
            item {
                PartsPreferenceGroup(title = stringResource(R.string.parts_device_status)) {
                    PartsPreference(
                        title = stringResource(R.string.network_title),
                        summary = stringResource(R.string.network_home_summary),
                        icon = Icons.Filled.Wifi,
                        onClick = { onNavigate("network") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_temperature_title),
                        summary = stringResource(R.string.parts_temperature_summary),
                        icon = Icons.Filled.DeviceThermostat,
                        onClick = { onNavigate("temperature") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_battery_title),
                        summary = stringResource(R.string.parts_battery_summary),
                        icon = Icons.Filled.BatteryChargingFull,
                        onClick = { onNavigate("battery") },
                    )
                }
            }
        }
    }
}

/**
 * About-phone style hero: device identity on the left, the device render on
 * the right. The render is a product asset the tree does not generate:
 * place it at res/drawable-nodpi/galaxian_device.png (or .webp).
 */
@Composable
private fun DeviceHero() {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.parts_device_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.parts_device_chip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.about_os_version, android.os.Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Image(
                painter = painterResource(R.drawable.galaxian_device),
                contentDescription = stringResource(R.string.about_image_desc),
                modifier = Modifier.height(156.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private data class Spec(val label: Int, val value: String, val icon: ImageVector)

@Composable
private fun SpecGrid() {
    val context = LocalContext.current
    // Measured once per composition: physical RAM and the userdata partition total.
    // Static marketing strings remain the fallback, never a fabricated zero.
    val ram = remember(context) { measuredRamGb(context) }
    val storage = remember { measuredStorageGb() }
    val specs = listOf(
        Spec(R.string.about_spec_processor, stringResource(R.string.about_spec_processor_value),
            Icons.Filled.DeveloperBoard),
        Spec(R.string.about_spec_camera, stringResource(R.string.about_spec_camera_value),
            Icons.Filled.PhotoCamera),
        Spec(R.string.about_spec_ram,
            ram?.let { stringResource(R.string.about_gigabytes, it) }
                ?: stringResource(R.string.about_spec_ram_value),
            Icons.Filled.Memory),
        Spec(R.string.about_spec_storage,
            storage?.let { stringResource(R.string.about_gigabytes, it) }
                ?: stringResource(R.string.about_spec_storage_value),
            Icons.Filled.SdStorage),
        Spec(R.string.about_spec_battery, stringResource(R.string.about_spec_battery_value),
            Icons.Filled.BatteryChargingFull),
        Spec(R.string.about_spec_screen, stringResource(R.string.about_spec_screen_value),
            Icons.Filled.Smartphone),
    )
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        specs.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { spec ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(spec.label),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = spec.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Physical RAM in decimal GB. Null when the service is unreachable. */
private fun measuredRamGb(context: Context): Long? = runCatching {
    val activity = context.getSystemService(ActivityManager::class.java) ?: return null
    val info = ActivityManager.MemoryInfo()
    activity.getMemoryInfo(info)
    (info.totalMem / 1_000_000_000.0).roundToLong().takeIf { it in 1..1024 }
}.getOrNull()

/** Userdata partition total in decimal GB. Null when the path is unreadable. */
private fun measuredStorageGb(): Long? = runCatching {
    (StatFs(Environment.getDataDirectory().path).totalBytes / 1_000_000_000.0)
        .roundToLong().takeIf { it in 8..8192 }
}.getOrNull()
