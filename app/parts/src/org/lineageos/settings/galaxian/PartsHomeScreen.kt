/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Temperature
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import org.lineageos.settings.galaxian.battery.BatterySnapshot
import org.lineageos.settings.galaxian.battery.readBatterySnapshot
import org.lineageos.settings.galaxian.temperature.TemperatureRepository
import org.lineageos.settings.galaxian.temperature.TemperatureSnapshot
import org.lineageos.settings.galaxian.ui.IconTile
import org.lineageos.settings.galaxian.ui.PartsCategoryTitle
import org.lineageos.settings.galaxian.ui.PartsGroupShape
import org.lineageos.settings.galaxian.ui.PartsPageGutter
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold
import org.lineageos.settings.galaxian.ui.rememberPartsResumed

@Composable
fun PartsHomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val resumed = rememberPartsResumed()
    val temperatureRepository = remember(appContext) { TemperatureRepository(appContext) }

    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    var thermal by remember { mutableStateOf<TemperatureSnapshot?>(null) }
    var ram by remember { mutableStateOf<RamReading?>(null) }

    // Live glanceable readings, sampled only while the Home screen is resumed.
    LaunchedEffect(resumed, appContext, temperatureRepository) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            val batteryReading = withContext(Dispatchers.IO) {
                runCatching { readBatterySnapshot(appContext) }.getOrNull()
            }
            val thermalReading = withContext(Dispatchers.IO) {
                runCatching { temperatureRepository.read() }.getOrNull()
            }
            val ramReading = withContext(Dispatchers.IO) {
                runCatching { readRam(appContext) }.getOrNull()
            }
            if (batteryReading != null) battery = batteryReading
            if (thermalReading != null) thermal = thermalReading
            if (ramReading != null) ram = ramReading
            delay(5_000)
        }
    }

    PartsScaffold(title = stringResource(R.string.parts_title)) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item("hero") { DeviceHero() }
            item("readings") { ReadingStrip(battery, thermal, ram, onNavigate) }
            item("performance_title") {
                PartsCategoryTitle(stringResource(R.string.parts_performance))
            }
            item("performance") { FeatureGrid(onNavigate) }
            item("device_title") {
                PartsCategoryTitle(stringResource(R.string.parts_device_status))
            }
            item("device") { DeviceLinks(onNavigate) }
            item("hardware") { HardwareCard() }
        }
    }
}

private data class RamReading(val usedBytes: Long)

private fun readRam(context: Context): RamReading? {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return null
    return runCatching {
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem.takeIf { it > 0 } ?: return@runCatching null
        RamReading(
            usedBytes = (total - info.availMem).coerceIn(0L, total),
        )
    }.getOrNull()
}

/**
 * About-phone style hero: device identity on the left, the device render on
 * the right, over a quiet dynamic-colour wash.
 */
@Composable
private fun DeviceHero() {
    val background = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surfaceBright,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp)
            .clip(PartsGroupShape)
            .background(background),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.parts_device_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.parts_device_chip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.about_os_version, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

/** Three at-a-glance readings; each card opens the page that owns the value. */
@Composable
private fun ReadingStrip(
    battery: BatterySnapshot?,
    thermal: TemperatureSnapshot?,
    ram: RamReading?,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val level = battery?.level
    val charging = battery?.status == BatteryManager.BATTERY_STATUS_CHARGING
    val batteryTint = when {
        level == null -> MaterialTheme.colorScheme.onSurfaceVariant
        level <= 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val thermalStatus = thermal?.thermalStatus
    val thermalTint = when {
        thermalStatus == null -> MaterialTheme.colorScheme.onSurfaceVariant
        thermalStatus >= Temperature.THROTTLING_SEVERE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val unavailable = "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReadingCard(
            label = stringResource(R.string.battery_title),
            value = level?.let { stringResource(R.string.battery_percent, it) } ?: unavailable,
            icon = if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
            tint = batteryTint,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate("battery") },
        )
        ReadingCard(
            label = stringResource(R.string.temperature_type_cpu),
            value = thermal?.cpuMaximumC?.let {
                stringResource(R.string.temperature_celsius, it)
            } ?: unavailable,
            icon = Icons.Filled.DeviceThermostat,
            tint = thermalTint,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate("temperature") },
        )
        ReadingCard(
            label = stringResource(R.string.memory_used),
            value = ram?.let { Formatter.formatFileSize(context, it.usedBytes) } ?: unavailable,
            icon = Icons.Filled.Memory,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate("memory") },
        )
    }
}

/** Compact value-first tile; three fit across a phone without truncating labels. */
@Composable
private fun ReadingCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class Feature(
    val title: Int,
    val summary: Int,
    val icon: ImageVector,
    val route: String,
    val container: Color,
    val tint: Color,
)

@Composable
private fun FeatureGrid(onNavigate: (String) -> Unit) {
    val features = listOf(
        Feature(
            R.string.parts_cpu_title,
            R.string.parts_cpu_summary,
            Icons.Filled.Speed,
            "cpu",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        Feature(
            R.string.gpu_settings_title,
            R.string.gpu_home_summary,
            Icons.Filled.DeveloperBoard,
            "gpu",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        Feature(
            R.string.parts_adaptive_title,
            R.string.parts_adaptive_summary,
            Icons.Filled.AutoAwesome,
            "adaptive",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        Feature(
            R.string.tuning_title,
            R.string.tuning_home_summary,
            Icons.Filled.Tune,
            "tuning",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        features.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { feature ->
                    FeatureTile(
                        feature = feature,
                        onClick = { onNavigate(feature.route) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    feature: Feature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    icon = feature.icon,
                    size = 44.dp,
                    container = feature.container,
                    tint = feature.tint,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(feature.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(feature.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceLinks(onNavigate: (String) -> Unit) {
    PartsPreferenceGroup {
        PartsPreference(
            title = stringResource(R.string.network_title),
            summary = stringResource(R.string.network_home_summary),
            icon = Icons.Filled.Wifi,
            onClick = { onNavigate("network") },
        )
        PartsPreference(
            title = stringResource(R.string.parts_memory_title),
            summary = stringResource(R.string.parts_memory_summary),
            icon = Icons.Filled.Memory,
            onClick = { onNavigate("memory") },
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

private data class Spec(
    val label: Int,
    val value: String,
    val icon: ImageVector,
)

/** The hardware summary retained from the previous Home screen. */
@Composable
private fun HardwareCard() {
    val context = LocalContext.current
    // Measured once per composition: physical RAM and the userdata partition total.
    // Static marketing strings remain the fallback, never a fabricated zero.
    val ram = remember(context) { measuredRamGb(context) }
    val storage = remember { measuredStorageGb() }
    val specs = listOf(
        Spec(
            R.string.about_spec_processor,
            stringResource(R.string.about_spec_processor_value),
            Icons.Filled.Speed,
        ),
        Spec(
            R.string.about_spec_camera,
            stringResource(R.string.about_spec_camera_value),
            Icons.Filled.PhotoCamera,
        ),
        Spec(
            R.string.about_spec_ram,
            ram?.let { stringResource(R.string.about_gigabytes, it) }
                ?: stringResource(R.string.about_spec_ram_value),
            Icons.Filled.Memory,
        ),
        Spec(
            R.string.about_spec_storage,
            storage?.let { stringResource(R.string.about_gigabytes, it) }
                ?: stringResource(R.string.about_spec_storage_value),
            Icons.Filled.SdStorage,
        ),
        Spec(
            R.string.about_spec_battery,
            stringResource(R.string.about_spec_battery_value),
            Icons.Filled.BatteryChargingFull,
        ),
        Spec(
            R.string.about_spec_screen,
            stringResource(R.string.about_spec_screen_value),
            Icons.Filled.Smartphone,
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            specs.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    row.forEach { spec ->
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(spec.label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = spec.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
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
