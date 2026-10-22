package com.duoopen.fold

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Device families whose fold doesn't fit the "cover panel + inner panel" model.
 *
 * Dual-screen (Surface Duo 1/2): two physical screens either side of a hinge,
 * presented as ONE logical display (Duo 1: display 0, 2784x1800 landscape,
 * an 84 px hinge gap centred at x = 1392) that never swaps panels — it just
 * turns off when closed. The hinge always splits the display's long side, and
 * both halves move, so the whole display is the "inner panel" and the fold is
 * driven by motion, not by a panel swap (see PanelEngine).
 */
object DeviceProfile {
    /** `adb shell settings put global duoopen_test_dual_screen 1` treats any device as dual-screen. */
    const val TEST_DUAL_SCREEN_SETTING = "duoopen_test_dual_screen"

    private val DUAL_SCREEN_DEVICES = setOf("duo", "duo2")

    @Volatile
    private var cached: Boolean? = null

    /** True on a Surface Duo (or when forced for testing). Read once per process. */
    fun isDualScreen(context: Context): Boolean = cached ?: compute(context).also { cached = it }

    private fun compute(context: Context): Boolean {
        val duo = Build.MANUFACTURER.equals("microsoft", ignoreCase = true) &&
            Build.DEVICE.lowercase() in DUAL_SCREEN_DEVICES
        val forced = runCatching {
            Settings.Global.getInt(context.contentResolver, TEST_DUAL_SCREEN_SETTING, 0) != 0
        }.getOrDefault(false)
        return duo || forced
    }
}
