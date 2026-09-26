package com.duoopen.shell

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.duoopen.fold.HingeAngleSource

/**
 * Continuous hinge angle on Samsung foldables, where the public sensor only
 * reports 0/90/180: Samsung's own "Fold interactive" home wallpaper receives
 * the real angle, and logs it (`mCurrentAngle=…`) whenever it's sent a
 * wallpaper command. So: keep a 1×1 wallpaper-showing anchor window, ping the
 * wallpaper through it at [POLL_MS], and let the Shizuku-side log reader
 * ([DuoShellService]) call back with each value, which is fed into
 * [HingeAngleSource] as the live angle.
 *
 * Technique from Duo Fold Live (github.com/joeconsorti/duo-fold-live, MIT).
 * Requires: Shizuku authorised, and that wallpaper set as the home wallpaper.
 */
class WallpaperAngleFeed(
    private val context: Context,
    private val handler: Handler,
    private val hinge: HingeAngleSource,
) {
    private var running = false
    private var action = ""
    private var anchor: View? = null
    private var anchorWm: WindowManager? = null
    private var anchorKey = ""
    private var lastCallbackUptime = 0L

    /** One line for the UI. */
    @Volatile
    var status: String = "Idle"
        private set

    val active: Boolean get() = running

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive != false) {
                runCatching { ensureAnchor() }.onFailure { status = "Anchor failed: ${it.message}" }
                val a = anchor
                if (a != null && a.windowToken != null) {
                    runCatching {
                        WallpaperManager.getInstance(a.context).sendWallpaperCommand(a.windowToken, action, 0, 0, 0, null)
                    }.onFailure { status = "Wallpaper command failed: ${it.message}" }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val b = ShizukuBridge.angleStatus()
            val age = if (lastCallbackUptime == 0L) -1 else SystemClock.uptimeMillis() - lastCallbackUptime
            status = if (b == null) "Reader unreachable" else
                "Reader ${b.getString("state")}: ${b.getInt("parsed")} angles / ${b.getInt("lines")} lines" +
                    (if (age >= 0) " · last ${age} ms ago" else " · nothing received yet")
            if (age > STALE_MS || (age < 0 && SystemClock.uptimeMillis() - startedAt > STALE_MS)) hinge.clearExternal()
            handler.postDelayed(this, 1_000)
        }
    }
    private var startedAt = 0L

    fun start() {
        if (running) return
        if (!ShizukuBridge.ready) { status = "Shizuku not ready"; return }
        if (!foldWallpaperActive(context)) { status = "Samsung's Fold interactive wallpaper isn't the home wallpaper"; return }
        action = "com.duoopen.angle.READ_${SystemClock.elapsedRealtime()}"
        if (!ShizukuBridge.startAngles(action) { angle -> handler.post { onAngle(angle) } }) {
            status = "Couldn't start the log reader"
            return
        }
        running = true
        startedAt = SystemClock.uptimeMillis()
        status = "Starting"
        Log.i(TAG, "wallpaper angle feed started ($action)")
        handler.post(poll)
        handler.postDelayed(statusTick, 1_000)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(statusTick)
        ShizukuBridge.stopAngles()
        hinge.clearExternal()
        removeAnchor()
        status = "Stopped"
        Log.i(TAG, "wallpaper angle feed stopped")
    }

    /** Re-check the anchor after a panel swap. */
    fun onDisplayChanged() {
        if (running) runCatching { ensureAnchor() }
    }

    private fun onAngle(angle: Float) {
        if (!running) return
        lastCallbackUptime = SystemClock.uptimeMillis()
        hinge.feedExternal(angle)
    }

    private fun displayKey(d: Display): String {
        val m = runCatching { d.mode }.getOrNull() ?: return "${d.displayId}"
        return "${d.displayId}:${m.physicalWidth}x${m.physicalHeight}"
    }

    /**
     * The wallpaper only answers commands from its current target window, so
     * the anchor shows wallpaper and lives on the default display; it is
     * re-created when that display swaps panels.
     */
    private fun ensureAnchor() {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val key = displayKey(display)
        if (anchor != null && key == anchorKey) return
        removeAnchor()
        val c = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = c.getSystemService(WindowManager::class.java)
        val v = View(c)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DuoOpenAngleAnchor"
        }
        wm.addView(v, params)
        anchor = v
        anchorWm = wm
        anchorKey = key
    }

    private fun removeAnchor() {
        val v = anchor ?: return
        runCatching { anchorWm?.removeViewImmediate(v) }
        anchor = null
        anchorWm = null
        anchorKey = ""
    }

    companion object {
        private const val TAG = "DuoAngleFeed"
        const val POLL_MS = 33L
        private const val STALE_MS = 2_500L
        val FOLD_WALLPAPER = ComponentName(
            "com.samsung.android.wallpaper.live",
            "com.samsung.android.wallpaper.live.fold.FoldInteractive",
        )

        /** Whether Samsung's fold-reactive wallpaper is the current home wallpaper. */
        fun foldWallpaperActive(context: Context): Boolean {
            val info = runCatching { WallpaperManager.getInstance(context).wallpaperInfo }.getOrNull() ?: return false
            return info.packageName == FOLD_WALLPAPER.packageName &&
                (info.serviceName == FOLD_WALLPAPER.className || info.serviceName.contains("FoldInteractive"))
        }
    }
}
