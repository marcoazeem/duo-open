package com.duoopen.fold

import android.view.Display
import kotlin.math.max
import kotlin.math.min

/** Book-style inner displays are near-square; cover screens are tall. */
private const val INNER_MAX_ASPECT = 1.45f

/**
 * True when [display] is currently driving the inner (unfolded) panel. Uses
 * the physical mode, which flips the moment the OnePlus Open swaps panels on
 * display 0 (~20° into an unfold) — before any window has been resized.
 */
fun Display?.isInnerPanel(): Boolean {
    val mode = runCatching { this?.mode }.getOrNull() ?: return false
    val a = mode.physicalWidth.toFloat()
    val b = mode.physicalHeight.toFloat()
    if (a <= 0f || b <= 0f) return false
    return max(a, b) / min(a, b) < INNER_MAX_ASPECT
}

/**
 * True for a built-in panel that is showing something: the default display,
 * or any other display that isn't a presentation/virtual one (cast, DeX,
 * MediaProjection), in any state but off. Doze counts — the cover screen
 * plays the effect from its always-on clock.
 *
 * @param includePresentation Also accept presentation-flagged displays. Only
 *   for testing the two-panels-lit path on an emulator with a developer-option
 *   "simulated secondary display" (`settings put global overlay_display_devices …`).
 */
fun Display.isLivePanel(defaultDisplayName: String?, includePresentation: Boolean = false): Boolean {
    if (!isValid) return false
    val rejected = if (includePresentation) Display.FLAG_PRIVATE else Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE
    // Samsung foldables expose the inner panel as a second logical display
    // that carries FLAG_PRESENTATION (Galaxy Z Fold 8: display 1, 2448x1848).
    // It shares the built-in panels' name with display 0 ("Built-in Screen"),
    // which cast, DeX and virtual displays don't, so the name is the tell.
    val sameNameAsDefault = defaultDisplayName != null && name == defaultDisplayName && (flags and Display.FLAG_PRIVATE) == 0
    val builtIn = displayId == Display.DEFAULT_DISPLAY || sameNameAsDefault || (flags and rejected) == 0
    return builtIn && state != Display.STATE_OFF && state != Display.STATE_UNKNOWN
}
