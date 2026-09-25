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
 */
fun Display.isLivePanel(): Boolean {
    if (!isValid) return false
    val builtIn = displayId == Display.DEFAULT_DISPLAY ||
        (flags and (Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE)) == 0
    return builtIn && state != Display.STATE_OFF && state != Display.STATE_UNKNOWN
}
