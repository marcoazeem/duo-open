package com.duoopen.overlay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lite edition: live wallpaper only. No accessibility service is declared or
 * compiled in, so nothing for Play Protect or restricted settings to block.
 */
object OverlayFeature {
    const val AVAILABLE = false
    const val SHIZUKU_AVAILABLE = false

    fun initProcess(context: Context) = Unit

    fun isEnabled(context: Context): Boolean = false

    fun liveBlurSupported(context: Context): Boolean = false

    fun playDemo(): Boolean = false

    val shizukuStatus: StateFlow<String> = MutableStateFlow("Not part of the lite edition.")
    fun shizukuReady(): Boolean = false
    fun shizukuInstalled(): Boolean = false
    fun shizukuNeedsPermission(): Boolean = false
    fun requestShizuku() = Unit
    fun refreshShizuku() = Unit
    fun foldWallpaperActive(context: Context): Boolean = false
    fun angleFeedStatus(): String = ""
}
