package com.duoopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.duoopen.fold.HingeAngleSource
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings, top to bottom in the order a new user needs them: where the
 * effect plays → how it's drawn → what it looks like → which parts move →
 * the wallpaper image → the hinge sensor (diagnostics). A modal sheet is its
 * own window, so it stays crisp while the screen behind it folds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSheet(
    config: DuoConfig,
    hinge: HingeAngleSource,
    hingeAngle: Float,
    paneTilt: Float,
    simulate: Boolean,
    onSimulateChange: (Boolean) -> Unit,
    simulatedAngle: Float,
    onSimulatedAngleChange: (Float) -> Unit,
    onPickImage: () -> Unit,
    onDefaultImage: () -> Unit,
    onSetWallpaper: () -> Unit,
    wallpaperActive: Boolean,
    overlayAvailable: Boolean,
    overlayEnabled: Boolean,
    liveBlurSupported: Boolean,
    dualStatus: String,
    dualActive: Boolean,
    onDualChange: (Boolean) -> Unit,
    shizukuAvailable: Boolean,
    shizukuStatus: String,
    shizukuReady: Boolean,
    shizukuInstalled: Boolean,
    onShizukuAuthorize: () -> Unit,
    onOpenShizuku: () -> Unit,
    foldWallpaperActive: Boolean,
    angleFeedStatus: () -> String,
    onOpenWallpaperSettings: () -> Unit,
    onEnableOverlay: () -> Unit,
    onTestOverlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val hasSensor = hinge.sensor != null
    // The sensor line isn't Compose state; poll it while the sheet is up.
    val sensorStatus by produceState(hinge.statusText(), hinge) {
        while (true) {
            delay(250)
            value = hinge.statusText()
        }
    }
    var showGuide by remember { mutableStateOf(false) }
    if (showGuide) {
        SetupGuide(
            onOpenAccessibility = { showGuide = false; onEnableOverlay() },
            onDismiss = { showGuide = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // 1. Where it plays -------------------------------------------------
            Section("Where it plays")
            if (overlayAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Whole screen", style = MaterialTheme.typography.titleSmall)
                        Hint(
                            if (overlayEnabled) "On. Folds everything — your wallpaper, icons, lock screen, open apps."
                            else "Off. Needs the accessibility service “Duo Open full-screen fold”.",
                        )
                    }
                    if (overlayEnabled) {
                        Button(onClick = onTestOverlay) { Text("Test") }
                    } else {
                        Button(onClick = onEnableOverlay) { Text("Turn on") }
                    }
                }
                if (overlayEnabled) {
                    TextButton(onClick = onEnableOverlay) { Text("Accessibility settings") }
                } else {
                    TextButton(onClick = { showGuide = true }) { Text("Toggle greyed out? Read this") }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper only", style = MaterialTheme.typography.titleSmall)
                    Hint(
                        when {
                            wallpaperActive -> "Active. The home and lock screen wallpaper folds; icons stay sharp."
                            overlayAvailable -> "Alternative that needs no accessibility service. Only the wallpaper folds."
                            else -> "This edition folds the home and lock screen wallpaper; icons and apps stay sharp."
                        },
                    )
                }
                OutlinedButton(onClick = onSetWallpaper) { Text(if (wallpaperActive) "Change" else "Set") }
            }

            // 2. How it's drawn -------------------------------------------------
            if (overlayAvailable) {
                Divider()
                Section("How it's drawn", "Applies to the whole-screen fold.")
                Choice(
                    options = listOf(false to "Snapshot", true to "Live blur"),
                    selected = config.liveBlur,
                    enabled = { !it || liveBlurSupported },
                    onSelect = { v -> DuoSettings.update { it.copy(liveBlur = v) } },
                )
                Hint(
                    if (config.liveBlur && liveBlurSupported) {
                        "Blurs the real screen with the system blur, live. No screenshot, no delay after the panel switches, " +
                            "and content keeps moving. The frost is stepped and there is no perspective bend."
                    } else {
                        "Takes one screenshot per fold and bends it like glass. Smooth frost and perspective, " +
                            "but the picture is frozen while it plays and there is a short delay after a panel switches on."
                    },
                )
                if (!liveBlurSupported) {
                    Hint("Live blur isn't available: this device has window blur turned off.", warn = true)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start instantly after a screen switches on", style = MaterialTheme.typography.titleSmall)
                        Hint(
                            "Begins from that screen's last picture and swaps in a fresh capture ~0.3 s later. " +
                                "Off: waits for the fresh capture, so the frost appears late. Snapshot mode only.",
                        )
                    }
                    Switch(
                        checked = config.instantStart,
                        onCheckedChange = { v -> DuoSettings.update { it.copy(instantStart = v) } },
                        enabled = !(config.liveBlur && liveBlurSupported),
                    )
                }
            }

            // 3. Look ---------------------------------------------------------
            Divider()
            Section("Look")
            LabeledSlider(
                label = "Strength",
                hint = "How heavy the frost gets. 1× follows the real hinge angle.",
                valueText = "%.2f×".format(config.intensity),
                value = config.intensity,
                onValueChange = { v -> DuoSettings.update { it.copy(intensity = v) } },
                range = 0.5f..3f,
            )
            LabeledSlider(
                label = "Frost",
                hint = "How quickly the blur grows away from the crease.",
                valueText = "%.2f".format(config.blurSpread),
                value = config.blurSpread,
                onValueChange = { v -> DuoSettings.update { it.copy(blurSpread = v) } },
                range = 0.02f..0.3f,
            )
            LabeledSlider(
                label = "Darkening",
                hint = "How much the frosted glass dims what's behind it.",
                valueText = "%.3f".format(config.darkening),
                value = config.darkening,
                onValueChange = { v -> DuoSettings.update { it.copy(darkening = v) } },
                range = 0f..0.04f,
            )
            LabeledSlider(
                label = "Eye distance",
                hint = "Perspective. Closer = more bend at the far edge. Snapshot mode only.",
                valueText = "${config.eyeDistanceMm.roundToInt()} mm",
                value = config.eyeDistanceMm,
                onValueChange = { v -> DuoSettings.update { it.copy(eyeDistanceMm = v) } },
                range = 200f..800f,
                enabled = !(config.liveBlur && liveBlurSupported),
            )
            TextButton(onClick = DuoSettings::resetTuning) { Text("Reset look") }

            // 4. Which parts move ----------------------------------------------
            Divider()
            Section("Which parts move")
            Text("Inner screen: half that swings", style = MaterialTheme.typography.titleSmall)
            Hint("Pick the half you don't hold. It frosts; the crease stays sharp.")
            Choice(
                options = listOf(-1 to "Left", 1 to "Right", 0 to "Both"),
                selected = config.movingSide,
                onSelect = { side -> DuoSettings.update { it.copy(movingSide = side) } },
            )
            Spacer(Modifier.height(8.dp))
            Text("Cover screen: frost comes from", style = MaterialTheme.typography.titleSmall)
            Hint("Which edge frosts first as you start opening; it clears the same way on closing.")
            Choice(
                options = listOf(true to "Right edge", false to "Left edge"),
                selected = config.coverFrostFromRight,
                onSelect = { v -> DuoSettings.update { it.copy(coverFrostFromRight = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Crease runs across the ${if (config.foldSplitsLong) "long" else "short"} side", style = MaterialTheme.typography.titleSmall)
                    Hint("Detected automatically. Flip only if the crease appears in the wrong place.")
                }
                TextButton(onClick = { DuoSettings.update { it.copy(foldSplitsLong = !it.foldSplitsLong) } }) { Text("Flip") }
            }

            // 5. Wallpaper image ----------------------------------------------
            Divider()
            Section("Wallpaper image", "Only used by the wallpaper mode and this preview. The whole-screen fold uses whatever is on screen.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) { Text("Choose image") }
                OutlinedButton(onClick = onDefaultImage, modifier = Modifier.weight(1f)) { Text("Use default") }
            }

            // 5b. Shizuku mode --------------------------------------------------
            if (shizukuAvailable) {
                Divider()
                Section(
                    "Shizuku mode (optional)",
                    "Shizuku gives apps ADB-level helpers without root. With it, Duo Open captures the screen " +
                        "with no rate limit and keeps the picture under the frost live, and on Samsung foldables " +
                        "reads the real hinge angle instead of just 0° / 90° / 180°.",
                )
                Hint(shizukuStatus, warn = !shizukuReady)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!shizukuReady) {
                        Button(onClick = onShizukuAuthorize, modifier = Modifier.weight(1f), enabled = shizukuInstalled) { Text("Authorise") }
                    }
                    OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.weight(1f)) {
                        Text(if (shizukuInstalled) "Open Shizuku" else "Get Shizuku")
                    }
                }
                if (shizukuReady) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fast live capture", style = MaterialTheme.typography.titleSmall)
                            Hint("Screen captured through Shizuku: instant after a panel switches, and the content under the frost keeps moving.")
                        }
                        Switch(checked = config.shizukuCapture, onCheckedChange = { v -> DuoSettings.update { it.copy(shizukuCapture = v) } })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Continuous hinge angle (Samsung)", style = MaterialTheme.typography.titleSmall)
                            Hint(
                                if (foldWallpaperActive) "Samsung's Fold interactive wallpaper is set — the real angle is read from it. " + angleFeedStatus()
                                else "Needs Samsung's built-in “Fold interactive” wallpaper as the home wallpaper (it's what receives the real angle). Set it, then come back.",
                                warn = !foldWallpaperActive,
                            )
                        }
                        Switch(checked = config.shizukuAngle, onCheckedChange = { v -> DuoSettings.update { it.copy(shizukuAngle = v) } })
                    }
                    if (!foldWallpaperActive) {
                        TextButton(onClick = onOpenWallpaperSettings) { Text("Open wallpaper settings") }
                    }
                }
            }

            // 6. Both screens at once ------------------------------------------
            Divider()
            Section(
                "Both screens at once (experimental)",
                "Some foldables can light the cover screen while the inner screen is in use. " +
                    "Turn this on with the phone open, then fold it: the whole-screen fold runs on both panels with no gap.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(dualStatus, modifier = Modifier.weight(1f))
                Switch(checked = dualActive, onCheckedChange = onDualChange, enabled = dualStatus.startsWith("Available") || dualActive)
            }

            // 7. Hinge sensor -------------------------------------------------
            Divider()
            Section("Hinge sensor", "Diagnostics. Nothing here changes the look.")
            Hint(sensorStatus)
            if (hasSensor && hinge.isCoarse) {
                Hint(
                    "This sensor only reports 0°, 90° and 180° (the continuous one is locked to system apps on " +
                        "Galaxy Z Fold 7 and earlier), so the fold plays as a short animation at each stop instead of " +
                        "tracking your hand.",
                    warn = true,
                )
            }
            Hint("Hinge ${if (hingeAngle.isNaN()) "—" else "${hingeAngle.roundToInt()}°"}  ·  pane tilt %.1f°".format(paneTilt))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Simulate the hinge", style = MaterialTheme.typography.titleSmall)
                    Hint("Drive the preview above with a slider instead of the real hinge.")
                }
                Switch(checked = simulate, onCheckedChange = onSimulateChange, enabled = hasSensor)
            }
            if (simulate) {
                LabeledSlider(
                    label = "Hinge angle",
                    hint = null,
                    valueText = "${simulatedAngle.roundToInt()}°",
                    value = simulatedAngle,
                    onValueChange = onSimulatedAngleChange,
                    range = 60f..180f,
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(hinge.report())) }) {
                Text("Copy sensor report for a bug report")
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (hint != null) Hint(hint)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 14.dp))

@Composable
private fun Hint(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> Choice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabled(value),
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    hint: String?,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hint != null) Hint(hint)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled)
    }
}
