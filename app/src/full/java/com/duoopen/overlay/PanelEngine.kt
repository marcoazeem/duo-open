package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The fold effect on one lit display: screenshot → touch-transparent overlay
 * drawn through the shader → tracks the hinge → removed at rest.
 *
 * One engine runs per live built-in panel (see [FoldOverlayService]). On a
 * phone that only ever lights one panel (OnePlus Open) that's a single engine
 * whose display flips between the cover and inner modes mid-fold; on a phone
 * that keeps both panels on, the cover and inner engines run side by side and
 * the effect hands over with no gap.
 *
 * Opening: leaving closed → capture on the cover → frost sweeps in; the inner
 * panel lights up → capture (retried while the panel is still black) → frost
 * clears to flat. Closing is the reverse. A frozen snapshot for the fraction
 * of a second of a fold is invisible in practice; if the hinge stops partway
 * (tent) the overlay fades out so live content isn't hidden.
 *
 * On a stops-only hinge sensor (Galaxy Z Fold 7 and earlier: 0/90/180) the
 * overlay can't follow the hinge, so each stop change plays a timed ease
 * instead — the same path the close-onto-cover already uses.
 */
class PanelEngine(
    private val service: AccessibilityService,
    val display: Display,
    private val hinge: HingeAngleSource,
    private val handler: Handler,
    private val scope: CoroutineScope,
    /** True when another engine is live on an inner panel right now. */
    private val hasLiveInnerElsewhere: () -> Boolean,
    private val onShowingChanged: () -> Unit,
) {
    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val displayId = display.displayId
    private val windowManager: WindowManager by lazy {
        service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }

    private var phase = Phase.IDLE
    private var overlay: FoldOverlayView? = null
    private var follower: TiltFollower? = null

    /** Which panel this display is driving; the effect restarts whenever it flips mid-fold. */
    var innerPanel = display.isInnerPanel()
        private set
    /** Set at a rest pose so leaving it plays once. */
    private var restArmed = true
    private var panelSwitched = false
    private var lastHingeMoveMs = 0L
    private var demoRunning = false
    /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
    private var captureGen = 0
    /** Attempt number of the capture in flight, so only its own timeout can give up. */
    private var captureAttempt = 0
    /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
    private var timedResolve = false

    val showing: Boolean get() = phase == Phase.SHOWING

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

    /** A timed frost-up that no panel swap has replaced: the fold stalled, let the live screen through. */
    private val peakHold = Runnable {
        if (timedResolve && !demoRunning) dismiss(fadeMs = FADE_OUT_STALLED_MS)
    }

    init {
        // A panel that lights up mid-fold is the second half of a fold in
        // progress; one that is already at rest just waits to leave it.
        val tilt = currentTilt()
        panelSwitched = tilt >= DuoShader.FLAT_EPSILON
        restArmed = !panelSwitched
        Log.i(TAG, "engine display=$displayId inner=$innerPanel midFold=$panelSwitched")
    }

    /** Whether this cover panel is lit alongside an inner panel (no swap will hand over). */
    private fun concurrentCover(): Boolean = !innerPanel && hasLiveInnerElsewhere()

    private fun tiltFor(angle: Float): Float {
        if (angle.isNaN()) return 0f
        val config = DuoSettings.config.value
        return when {
            innerPanel -> DuoShader.tiltForHinge(angle, config)
            concurrentCover() -> DuoShader.concurrentCoverTiltForHinge(angle, config)
            else -> DuoShader.coverTiltForHinge(angle, config)
        }
    }

    private fun currentTilt(): Float = tiltFor(hinge.lastAngle)

    fun onHinge(angle: Float) {
        lastHingeMoveMs = SystemClock.uptimeMillis()
        evaluate()
        val tilt = tiltFor(angle)
        if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
            // At rest: drop the overlay now rather than easing the last degrees.
            // (Also ends a timed play early if the hinge is back at rest.)
            dismiss(fadeMs = FADE_OUT_FLAT_MS)
        } else if (!timedResolve) {
            follower?.setTarget(tilt)
        }
    }

    /**
     * Drives the effect from two signals: which panel this display shows and
     * the hinge angle. The picture is flat at the panel's rest pose and fully
     * frosted at the swap, so an open or a close is one continuous frost-up on
     * the first panel and frost-down on the second. A capture starts on
     * leaving rest and again on each swap.
     */
    fun evaluate() {
        if (demoRunning) return
        val inner = display.isInnerPanel()
        if (inner != innerPanel) {
            innerPanel = inner
            panelSwitched = true
            if (phase != Phase.IDLE) removeOverlay() // old panel's snapshot is meaningless now
        }
        val angle = hinge.lastAngle
        if (angle.isNaN()) return
        val tilt = tiltFor(angle)
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
                Log.i(TAG, "display $displayId: panel swapped (inner=$inner) at hinge=$angle")
                // The fresh panel may still be lighting up: retry if black.
                startCapture(afterSwap = true)
            }
            restArmed && tilt >= REST_LEAVE_TILT -> {
                restArmed = false
                Log.i(TAG, "display $displayId: leaving rest (inner=$inner) at hinge=$angle")
                startCapture(afterSwap = false)
            }
        }
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        captureAttempt = attempt
        fun stale() = gen != captureGen || phase != Phase.CAPTURING
        // The framework refuses captures closer than ~333 ms apart, measured
        // from the previous request — so a slow capture costs no extra wait.
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
        }
        // A screenshot requested as a panel switches off may never call back.
        // Only the latest attempt's timeout counts: an earlier one must not
        // give up on behalf of a retry that is still in flight.
        handler.postDelayed({
            if (!stale() && captureAttempt == attempt) {
                Log.w(TAG, "display $displayId: capture $attempt timed out; giving up")
                phase = Phase.IDLE
                demoRunning = false
            }
        }, CAPTURE_TIMEOUT_MS)
        service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "display $displayId: stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "display $displayId: screenshot buffer could not be wrapped")
                    phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
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
                        Log.i(TAG, "display $displayId: capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                // Secure content (banking, DRM video) and rate limits land here.
                Log.w(TAG, "display $displayId: screenshot failed: $errorCode")
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                    attempt < MAX_CAPTURE_ATTEMPTS
                ) {
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
        val live = startTilt == null
        val coarse = live && hinge.isCoarse
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // A stops-only sensor can't be followed, so each stop change plays a
        // fixed ease: frost in on leaving rest, frost out on the fresh panel.
        val tilt = startTilt ?: if (coarse) (if (afterSwap) peak else DuoShader.FLAT_EPSILON * 1.2f) else currentTilt()
        val nearlyDone = afterSwap && live &&
            if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "display $displayId: hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        // Closing onto a cover that only lights after the swap: the hinge HAL
        // goes quiet around 30°, so the overlay would never hear "closed".
        // Resolve on a timer instead — by the time this capture lands the
        // phone is shut anyway, so it reads as the cover settling into focus.
        // A cover lit alongside the inner panel keeps hearing the hinge.
        val easeTo = when {
            afterSwap && !innerPanel && !concurrentCover() && live -> 0f
            coarse -> if (afterSwap) 0f else peak
            else -> null
        }
        Log.i(TAG, "display $displayId: showing ${bitmap.width}x${bitmap.height} at tilt=$tilt (capture ${SystemClock.uptimeMillis() - t0}ms)${easeTo?.let { " easing to $it" } ?: ""}")
        show(bitmap, tilt, fadeIn = afterSwap, easeTo = easeTo)
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

    /**
     * [easeTo] non-null plays a timed ease to that tilt, ignoring the hinge
     * until it's back at rest; easing up to a frosted peak holds there
     * briefly, then fades unless a panel swap has replaced it.
     */
    private fun show(bitmap: Bitmap, startTilt: Float, fadeIn: Boolean = false, easeTo: Float? = null) {
        val inner = innerPanel
        val view = FoldOverlayView(service, bitmap, { w, h, c -> DuoShader.foldFor(inner, w, h, c) }).apply {
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
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "display $displayId: addView failed", e)
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        overlay = view
        phase = Phase.SHOWING
        onShowingChanged()
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
        timedResolve = easeTo != null
        if (easeTo != null) {
            follower?.tauS = if (hinge.isCoarse) COARSE_EASE_TAU_S else TIMED_RESOLVE_TAU_S
            follower?.setTarget(easeTo)
            if (easeTo > DuoShader.FLAT_EPSILON) handler.postDelayed(peakHold, PEAK_HOLD_MS)
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    private fun dismiss(fadeMs: Long) {
        val view = overlay ?: return
        Log.i(TAG, "display $displayId: dismiss (fade ${fadeMs}ms) at tilt=${view.tilt}")
        clearOverlayState()
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detach(view) }
            .start()
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        timedResolve = false
        val view = overlay ?: return
        clearOverlayState()
        detach(view)
    }

    private fun clearOverlayState() {
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(peakHold)
        follower?.cancel()
        follower = null
        overlay = null
        timedResolve = false
        phase = Phase.IDLE
        onShowingChanged()
    }

    private fun detach(view: FoldOverlayView) {
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** The display went away or the service is stopping. */
    fun destroy() {
        captureGen++ // orphan any capture in flight
        removeOverlay()
        Log.i(TAG, "engine display=$displayId destroyed")
    }

    /**
     * Manual check without folding: snapshot the screen and play what this
     * panel shows during a fold. Inner panel: an unfold from full frost to
     * flat. Cover panel: frost sweeping in (opening) then back out (closing).
     */
    fun playDemo(durationMs: Long = 1400) {
        if (phase != Phase.IDLE || demoRunning) return
        demoRunning = true
        val inner = innerPanel
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // Frost at the very start so the overlay is visibly there; on the
        // cover it starts flat and sweeps in first.
        startCapture(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val f = follower
            if (f == null) {
                demoRunning = false
                return@postDelayed
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
        }, 450)
    }

    private companion object {
        const val TAG = "DuoOverlay"
        /** The framework rejects screenshots closer together than ~333 ms. */
        const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        const val MAX_CAPTURE_ATTEMPTS = 3
        /** A screenshot of a panel that is still lighting up can take most of a second. */
        const val CAPTURE_TIMEOUT_MS = 1_100L
        const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Slower ease for stops-only sensors, so a play reads as a fold (≈ 450 ms). */
        const val COARSE_EASE_TAU_S = 0.12f
        /** How long a timed frost-up stays before it fades, absent a panel swap. */
        const val PEAK_HOLD_MS = 1_200L
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        const val SKIP_INNER_ABOVE_HINGE = 135f
        const val SKIP_COVER_BELOW_HINGE = 10f
        const val SETTLE_TIMEOUT_MS = 700L
        const val FADE_IN_MS = 140L
        const val FADE_OUT_FLAT_MS = 120L
        const val FADE_OUT_STALLED_MS = 300L
    }
}
