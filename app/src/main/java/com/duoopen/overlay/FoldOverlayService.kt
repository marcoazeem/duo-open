package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System-wide fold effect. Accessibility services may screenshot the display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps — not just the wallpaper.
 *
 * Opening: the inner panel comes up → one screenshot (retried if the panel
 * was still black) → a full-screen, touch-transparent overlay draws it
 * through the fold shader, tracking the hinge → removed once flat.
 * Closing: the hinge leaves flat → screenshot → same overlay, removed when
 * the panel switches to the cover screen.
 *
 * The snapshot is frozen for the fraction of a second of a fold, which is
 * invisible in practice; if the hinge stops partway (tent mode) the overlay
 * fades out instead so live content isn't hidden.
 */
class FoldOverlayService : AccessibilityService() {

    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private val displayHandler = Handler(Looper.getMainLooper())
    private val panels = mutableMapOf<Int, PanelSession>()
    private var receiverRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncPanels()
        override fun onDisplayRemoved(displayId: Int) = syncPanels()
        override fun onDisplayChanged(displayId: Int) = syncPanels()
    }
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (instance === this) return
        instance = this
        displayManager = getSystemService(DisplayManager::class.java)
        hinge = HingeAngleSource(this) { angle ->
            panels.values.toList().forEach { it.onHinge(angle) }
        }
        registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO),
            "android.permission.DUMP", displayHandler, RECEIVER_EXPORTED)
        receiverRegistered = true
        displayManager.registerDisplayListener(displayListener, displayHandler)
        syncPanels()
        hinge.start()
        Log.i(TAG, "connected; hinge=${hinge.sensor?.name} displays=${panels.keys}")
    }

    private fun syncPanels() {
        // Samsung exposes its cover as display 1; OnePlus swaps modes on 0.
        // Never capture attached monitors or virtual displays.
        val primary = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val ids = displayManager.displays.filter {
            it.displayId == Display.DEFAULT_DISPLAY ||
                (android.os.Build.MANUFACTURER.equals("samsung", true) &&
                    it.displayId == 1 && it.name == primary?.name)
        }.map { it.displayId }.toSet()
        (panels.keys - ids).forEach { panels.remove(it)?.close() }
        ids.forEach { id -> panels.getOrPut(id) { PanelSession(id) }.evaluate() }
    }

    private fun updateRunning() = OverlayState.setRunning(panels.values.any { it.isShowing() })

    override fun onDestroy() {
        instance = null
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        if (::hinge.isInitialized) hinge.stop()
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        panels.values.forEach { it.close() }
        panels.clear()
        OverlayState.setRunning(false)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { panels.values.forEach { it.removeOverlay() } }

    fun playDemo(durationMs: Long = 700) {
        syncPanels()
        panels.filterKeys { displayManager.getDisplay(it)?.state == Display.STATE_ON }
            .values.forEach { it.playDemo(durationMs) }
    }

    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private inner class PanelSession(private val displayId: Int) {

        private val handler = Handler(Looper.getMainLooper())
        private val scope = MainScope()
        private var windowManager: WindowManager? = null

        private var phase = Phase.IDLE
        private var overlay: FoldOverlayView? = null
        private var visibleSnapshot: Bitmap? = null
        private var captureStartedMs = 0L
        private var follower: TiltFollower? = null

        /** Which panel is live; the effect restarts whenever this flips mid-fold. */
        private var innerPanel = defaultDisplay().isInnerPanel()
        private var wasOn = false
        private val fadingViews = mutableSetOf<FoldOverlayView>()
        /** Set at a rest pose (closed on the cover, flat on the inner) so leaving it plays once. */
        private var restArmed = true
        private var panelSwitched = false
        private var lastHingeMoveMs = 0L
        private var demoRunning = false
        private var demoDurationMs = 700L
        /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
        private var captureGen = 0
        private var captureAttempt = 0
        /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
        private var timedResolve = false

        private val settleCheck = object : Runnable {
            override fun run() {
                val o = overlay ?: return
                if (o.tilt < DuoShader.FLAT_EPSILON) return
                if (!demoRunning && SystemClock.uptimeMillis() - lastHingeMoveMs >= SETTLE_TIMEOUT_MS) {
                    dismiss(fadeMs = FADE_OUT_STALLED_MS)
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }

        fun close() {
            captureGen++
            handler.removeCallbacksAndMessages(null)
            removeOverlay()
            fadingViews.toList().forEach { it.animate().cancel(); detach(it) }
            scope.cancel()
        }

        private fun defaultDisplay(): Display? = displayManager.getDisplay(displayId)

        private fun currentTilt(): Float =
            DuoShader.tiltFor(hinge.lastAngle, DuoSettings.config.value, innerPanel)

        fun onHinge(angle: Float) {
            lastHingeMoveMs = SystemClock.uptimeMillis()
            evaluate()
            val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, innerPanel)
            if (timedResolve || demoRunning) return
            if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
                // At rest: drop the overlay now rather than easing the last degrees.
                dismiss(fadeMs = FADE_OUT_FLAT_MS)
            } else {
                follower?.setTarget(tilt)
            }
        }

        /**
         * Drives the effect from two signals: the live panel and the hinge angle.
         * Whichever panel is up, the picture is flat at its rest pose (cover
         * closed, inner open) and fully frosted at the panel swap, so an open or a
         * close is one continuous frost-up on the first panel and frost-down on
         * the second. A capture starts on leaving rest and again on each swap.
         */
        fun evaluate() {
            val on = defaultDisplay()?.state == Display.STATE_ON
            if (!on) {
                if (wasOn) { captureGen++; removeOverlay() }
                fadingViews.toList().forEach { it.animate().cancel(); detach(it) }
                wasOn = false
                restArmed = true
                return
            }
            if (!wasOn) { wasOn = true; panelSwitched = true }
            if (demoRunning) return
            val inner = defaultDisplay().isInnerPanel()
            if (inner != innerPanel) {
                innerPanel = inner
                panelSwitched = true
                if (phase != Phase.IDLE) removeOverlay() // old panel's snapshot is meaningless now
            }
            val angle = hinge.lastAngle
            if (angle.isNaN()) return
            val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, inner)
            if (tilt < DuoShader.FLAT_EPSILON) {
                restArmed = true
                panelSwitched = false
                return
            }
            if (phase != Phase.IDLE) return
            when {
                panelSwitched -> {
                    panelSwitched = false
                    restArmed = false
                    Log.i(TAG, "panel swapped (inner=$inner) at hinge=$angle")
                    // The fresh panel may still be lighting up: retry if black.
                    startCapture(afterSwap = true)
                }
                restArmed && tilt >= REST_LEAVE_TILT -> {
                    restArmed = false
                    Log.i(TAG, "leaving rest (inner=$inner) at hinge=$angle")
                    startCapture(afterSwap = false)
                }
            }
        }

        private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
            fadingViews.toList().forEach { it.animate().cancel(); detach(it) }
            phase = Phase.CAPTURING
            captureStartedMs = SystemClock.uptimeMillis()
            capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
        }

        private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
            val t0 = SystemClock.uptimeMillis()
            val ticket = ++captureAttempt
            fun stale() = gen != captureGen || ticket != captureAttempt || phase != Phase.CAPTURING
            // Samsung concurrent mode lights the cover without putting an app
            // task there. Share this fold's current inner snapshot, avoiding a
            // black cover capture and a second globally rate-limited request.
            val mirrorInner = displayId == 1 &&
                displayManager.getDisplay(0)?.state == Display.STATE_ON
            val source = if (mirrorInner) 0 else displayId
            if (mirrorInner) {
                val primary = panels[0]
                val shared = primary?.visibleSnapshot
                if (primary?.phase == Phase.SHOWING && shared != null && !shared.isRecycled) {
                    val buffer = shared.hardwareBuffer
                    val duplicate = try { Bitmap.wrapHardwareBuffer(buffer, shared.colorSpace) }
                        finally { buffer.close() }
                    if (duplicate != null) {
                        onCaptured(duplicate, afterSwap, startTilt, captureStartedMs)
                        return
                    }
                }
                if (primary?.phase == Phase.CAPTURING &&
                    SystemClock.uptimeMillis() - captureStartedMs < CAPTURE_TIMEOUT_MS) {
                    handler.postDelayed({ if (!stale()) capture(gen, attempt, afterSwap, startTilt) }, 16)
                    return
                }
            }
            // The framework refuses captures closer than ~333 ms apart, measured
            // from the previous request — so a slow capture costs no extra wait.
            fun retry() {
                val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
            }
            // A screenshot requested as a panel switches off may never call back.
            handler.postDelayed({
                if (!stale()) {
                    Log.w(TAG, "capture $attempt timed out; giving up")
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }, CAPTURE_TIMEOUT_MS)
            takeScreenshot(source, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (stale()) {
                        Log.i(TAG, "stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                        bitmap?.recycle()
                        return
                    }
                    if (bitmap == null) {
                        Log.w(TAG, "screenshot buffer could not be wrapped")
                        phase = Phase.IDLE
                        return
                    }
                    if (!afterSwap || mirrorInner) {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                        return
                    }
                    scope.launch {
                        val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                        if (stale()) {
                            bitmap.recycle()
                            return@launch
                        }
                        if (black && !demoRunning) {
                            bitmap.recycle()
                            Log.i(TAG, "capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                            if (attempt < MAX_CAPTURE_ATTEMPTS) retry()
                            else { phase = Phase.IDLE; demoRunning = false }
                        } else {
                            onCaptured(bitmap, afterSwap, startTilt, t0)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (stale()) return
                    // Secure content (banking, DRM video) and rate limits land here.
                    Log.w(TAG, "screenshot failed: $errorCode")
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_CAPTURE_ATTEMPTS) {
                        retry()
                    } else {
                        phase = Phase.IDLE
                        demoRunning = false
                    }
                }
            })
        }

        private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
            if (phase != Phase.CAPTURING) {
                bitmap.recycle()
                return
            }
            val angle = hinge.lastAngle
            val tilt = startTilt ?: currentTilt()
            val nearlyDone = afterSwap && startTilt == null &&
                if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
            if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
                // Too late to be worth a pop-in: the fold is (almost) over.
                Log.i(TAG, "hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
                bitmap.recycle()
                phase = Phase.IDLE
                return
            }
            // Closing onto the cover: the hinge HAL goes quiet around 30°, so the
            // overlay would never hear "closed". Resolve on a timer instead — by
            // the time this capture lands the phone is shut anyway, so it reads
            // as the cover settling into focus.
            val timed = afterSwap && !innerPanel && startTilt == null &&
                !android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            Log.i(TAG, "display=$displayId showing ${bitmap.width}x${bitmap.height} at tilt=$tilt (capture ${SystemClock.uptimeMillis() - t0}ms)${if (timed) " timed" else ""}")
            show(bitmap, tilt, fadeIn = afterSwap, timed = timed)
            if (demoRunning) animateDemo(demoDurationMs)
        }

        /** Samples a coarse grid; true when nothing on screen is brighter than near-black. */
        private fun isMostlyBlack(hw: Bitmap): Boolean {
            val sw = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
            try {
                val n = 24
                var maxSum = 0
                for (iy in 0 until n) {
                    val y = ((iy + 0.5f) * sw.height / n).toInt()
                    for (ix in 0 until n) {
                        val x = ((ix + 0.5f) * sw.width / n).toInt()
                        val c = sw.getPixel(x, y)
                        val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                        if (sum > maxSum) maxSum = sum
                    }
                }
                return maxSum < BLACK_THRESHOLD
            } finally {
                sw.recycle()
            }
        }

        private fun show(bitmap: Bitmap, startTilt: Float, fadeIn: Boolean = false, timed: Boolean = false) {
            val display = defaultDisplay()
            if (display == null || display.state != Display.STATE_ON || display.isInnerPanel() != innerPanel) {
                bitmap.recycle()
                removeOverlay()
                return
            }
            val wm = windowManager ?: createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                .getSystemService(WindowManager::class.java)
                .also { windowManager = it }

            val inner = innerPanel
            val view = FoldOverlayView(createDisplayContext(display), bitmap, { w, h, c -> DuoShader.foldFor(inner, w, h, c) }).apply {
                config = DuoSettings.config.value
                tilt = startTilt
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "DuoOpenFold"
            }
            try {
                wm.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
                bitmap.recycle()
                phase = Phase.IDLE
                return
            }
            visibleSnapshot = bitmap
            overlay = view
            phase = Phase.SHOWING
            updateRunning()
            if (fadeIn) {
                // Content was already live on this panel; ease the frost in.
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            }
            follower = TiltFollower { t ->
                view.tilt = t
                if (t < DuoShader.FLAT_EPSILON && !demoRunning) dismiss(fadeMs = FADE_OUT_FLAT_MS)
            }.also { it.snap(startTilt) }
            lastHingeMoveMs = SystemClock.uptimeMillis()
            timedResolve = timed
            if (timed) {
                follower?.tauS = TIMED_RESOLVE_TAU_S
                follower?.setTarget(0f)
            } else {
                handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
            }
        }

        private fun dismiss(fadeMs: Long) {
            val view = overlay ?: return
            Log.i(TAG, "dismiss (fade ${fadeMs}ms) at tilt=${view.tilt}")
            handler.removeCallbacks(settleCheck)
            follower?.cancel()
            follower = null
            overlay = null
            visibleSnapshot = null
            timedResolve = false
            phase = Phase.IDLE
            fadingViews.add(view)
            view.animate()
                .alpha(0f)
                .setDuration(fadeMs)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { detach(view) }
                .start()
        }

        fun removeOverlay() {
            handler.removeCallbacksAndMessages(null)
            demoRunning = false
            fadingViews.toList().forEach { it.animate().cancel(); detach(it) }
            captureGen++
            phase = Phase.IDLE
            timedResolve = false
            val view = overlay ?: return
            handler.removeCallbacks(settleCheck)
            follower?.cancel()
            follower = null
            overlay = null
            visibleSnapshot = null
            detach(view)
        }

        fun isShowing() = overlay != null || fadingViews.isNotEmpty()

        private fun detach(view: FoldOverlayView) {
            runCatching { windowManager?.removeViewImmediate(view) }
            fadingViews.remove(view)
            updateRunning()
        }

        /**
         * Manual check without folding: snapshot the screen and play what this
         * panel shows during a fold. Inner panel: an unfold from full frost to
         * flat. Cover panel: frost sweeping in (opening) then back out (closing).
         */
        fun playDemo(durationMs: Long = 1400) {
            if (phase != Phase.IDLE || demoRunning) return
            demoRunning = true
            demoDurationMs = durationMs
            val inner = innerPanel
            val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
            // Frost at the very start so the overlay is visibly there; on the
            // cover it starts flat and sweeps in first.
            startCapture(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        }

        private fun animateDemo(durationMs: Long) {
            val inner = innerPanel
            val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
            val f = follower
            if (f == null) {
                demoRunning = false
                return
            }
            // Slow ease so the demo reads as a fold rather than a snap.
            f.tauS = durationMs / 4000f
            if (inner) {
                f.setTarget(0f)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs)
            } else {
                f.setTarget(peak)
                handler.postDelayed({ f.setTarget(0f) }, durationMs)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs * 2)
            }
        }

    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        /** The framework rejects screenshots closer together than ~333 ms. */
        private const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        private const val MAX_CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_TIMEOUT_MS = 800L
        private const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        private const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        private const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        private const val SKIP_INNER_ABOVE_HINGE = 170f
        private const val SKIP_COVER_BELOW_HINGE = 10f
        private const val SETTLE_TIMEOUT_MS = 700L
        private const val FADE_IN_MS = 50L
        private const val FADE_OUT_FLAT_MS = 60L
        private const val FADE_OUT_STALLED_MS = 300L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}
