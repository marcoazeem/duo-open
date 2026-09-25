package com.duoopen.overlay

import android.content.Context

/**
 * Lite edition: live wallpaper only. No accessibility service is declared or
 * compiled in, so nothing for Play Protect or restricted settings to block.
 */
object OverlayFeature {
    const val AVAILABLE = false

    fun isEnabled(context: Context): Boolean = false

    fun liveBlurSupported(context: Context): Boolean = false

    fun playDemo(): Boolean = false
}
