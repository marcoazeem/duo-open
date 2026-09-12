package com.duoopen.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tuning shared by the in-app preview and the live wallpaper.
 *
 * @param intensity Multiplier on the physical pane tilt ((180 - hinge) / 2).
 *   1 = physically faithful; higher makes the frost linger further into the open.
 * @param blurSpread Blur radius gained per px of glass/plane separation.
 * @param darkening Fraction of light lost per px of blur radius, authored at the
 *   original's 6 units/mm reference density (normalized per display at draw time).
 * @param eyeDistanceMm Viewer distance from the flat screen plane.
 * @param foldSplitsLong Whether the fold line splits the display's longer side.
 *   Learned from Jetpack WindowManager while the app is open, so the wallpaper
 *   (which can't query fold features) puts the hinge in the right place.
 * @param movingSide Which half swings while the other is held still:
 *   -1 = left (top when the fold is horizontal), +1 = right (bottom), 0 = both.
 * @param coverFrostFromRight On the cover screen, frost grows from the right
 *   edge (hinge on the left); false mirrors it.
 * @param imageVersion Bumped whenever the wallpaper image changes.
 */
data class DuoConfig(
    val intensity: Float = 1f,
    val blurSpread: Float = 0.12f,
    val darkening: Float = 0.015f,
    val eyeDistanceMm: Float = 450f,
    val foldSplitsLong: Boolean = false,
    val movingSide: Int = -1,
    val coverFrostFromRight: Boolean = true,
    val imageVersion: Long = 0L,
    val keepBothDisplaysAwake: Boolean = false,
)

object DuoSettings {
    private const val PREFS = "duo_open"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(DuoConfig())
    val config: StateFlow<DuoConfig> = _config.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = DuoConfig()
        _config.value = DuoConfig(
            intensity = prefs.getFloat("intensity", d.intensity),
            blurSpread = prefs.getFloat("blurSpread", d.blurSpread),
            darkening = prefs.getFloat("darkening", d.darkening),
            eyeDistanceMm = prefs.getFloat("eyeDistanceMm", d.eyeDistanceMm),
            foldSplitsLong = prefs.getBoolean("foldSplitsLong", d.foldSplitsLong),
            movingSide = prefs.getInt("movingSide", d.movingSide),
            coverFrostFromRight = prefs.getBoolean("coverFrostFromRight", d.coverFrostFromRight),
            imageVersion = prefs.getLong("imageVersion", d.imageVersion),
            keepBothDisplaysAwake = prefs.getBoolean("keepBothDisplaysAwake", false),
        )
    }

    fun update(transform: (DuoConfig) -> DuoConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit {
            putFloat("intensity", next.intensity)
            putFloat("blurSpread", next.blurSpread)
            putFloat("darkening", next.darkening)
            putFloat("eyeDistanceMm", next.eyeDistanceMm)
            putBoolean("foldSplitsLong", next.foldSplitsLong)
            putInt("movingSide", next.movingSide)
            putBoolean("coverFrostFromRight", next.coverFrostFromRight)
            putLong("imageVersion", next.imageVersion)
            putBoolean("keepBothDisplaysAwake", next.keepBothDisplaysAwake)
        }
    }

    /** Restores the look defaults, keeping fold geometry, moving side and the image. */
    fun resetTuning() = update {
        DuoConfig(
            foldSplitsLong = it.foldSplitsLong,
            movingSide = it.movingSide,
            coverFrostFromRight = it.coverFrostFromRight,
            imageVersion = it.imageVersion,
            keepBothDisplaysAwake = it.keepBothDisplaysAwake,
        )
    }
}
