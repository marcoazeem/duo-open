package com.duoopen.overlay

import android.content.Context
import android.view.WindowManager

/** Full edition: the system-wide fold via [FoldOverlayService]. */
object OverlayFeature {
    const val AVAILABLE = true

    fun isEnabled(context: Context): Boolean = FoldOverlayService.isEnabled(context)

    /** Whether the system will blur behind our windows (the "live blur" engine). */
    fun liveBlurSupported(context: Context): Boolean =
        runCatching { context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true }
            .getOrDefault(false)

    /** Replays the effect over the current screen; false if the service isn't connected. */
    fun playDemo(): Boolean {
        val service = FoldOverlayService.instance ?: return false
        service.playDemo()
        return true
    }
}
