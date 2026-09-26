package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import android.view.SurfaceControl
import android.view.View
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
 *
 * Two ways to draw ([FoldSurface]): a warped screenshot (default), or — when
 * [com.duoopen.settings.DuoConfig.liveBlur] is on and the system allows
 * cross-window blur — the system blur over the live screen, which needs no
 * capture at all and so starts the instant a phase begins.
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
    /** Last capture of each panel kind, shared by all engines (see [SnapshotCache]). */
    private val cache: SnapshotCache,
) {
    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val displayId = display.displayId
    private val windowManager: WindowManager by lazy {
        service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }

    private var phase = Phase.IDLE
    private var surface: FoldSurface? = null
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
    /** Showing a cached picture of this panel while a fresh capture is in flight. */
    private var bridging = false
    /** Live re-capture loop (Shizuku mode) is scheduled. */
    private var liveLoop = false

    val showing: Boolean get() = phase == Phase.SHOWING

    private val settleCheck = object : Runnable {
        override fun run() {
            val o = surface ?: return
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
            val f = follower
            if (timedResolve && hinge.isCoarse && f != null && f.current > DuoShader.FLAT_EPSILON) {
                // Stops-only sensor: the rest stop (180° / 0°) is the first news
                // that the fold has finished, so clear from here — the frost
                // was held meanwhile. The follower dismisses at flat.
                handler.removeCallbacks(peakHold)
                f.tauS = COARSE_CLEAR_TAU_S
                f.setTarget(0f)
            } else {
                // At rest: drop the overlay now rather than easing the last degrees.
                dismiss(fadeMs = FADE_OUT_FLAT_MS)
            }
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
                startEffect(afterSwap = true)
            }
            restArmed && tilt >= REST_LEAVE_TILT -> {
                restArmed = false
                Log.i(TAG, "display $displayId: leaving rest (inner=$inner) at hinge=$angle")
                startEffect(afterSwap = false)
            }
        }
    }

    /** Live blur needs no capture: the system blurs whatever is on screen. */
    private fun liveMode(): Boolean =
        DuoSettings.config.value.liveBlur &&
            runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)

    private fun startEffect(afterSwap: Boolean, startTilt: Float? = null) {
        if (liveMode()) {
            phase = Phase.CAPTURING // same gate as a capture in flight, resolved synchronously
            present(bitmap = null, afterSwap = afterSwap, startTilt = startTilt, t0 = SystemClock.uptimeMillis())
            return
        }
        // A panel that has just switched on takes ~0.3 s to screenshot. Start
        // at once with the picture it showed last time and swap in the fresh
        // capture when it lands — the frost hides the difference.
        if (afterSwap && startTilt == null && DuoSettings.config.value.instantStart) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val stale = cache.get(innerPanel, bounds.width(), bounds.height())
            if (stale != null) {
                phase = Phase.CAPTURING
                Log.i(TAG, "display $displayId: bridging with a ${cache.ageMs(innerPanel)}ms-old snapshot")
                present(stale, afterSwap = true, startTilt = null, t0 = SystemClock.uptimeMillis())
                bridging = phase == Phase.SHOWING
            }
        }
        startCapture(afterSwap, startTilt)
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        if (!bridging) phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        captureAttempt = attempt
        fun stale() = gen != captureGen || (phase != Phase.CAPTURING && !bridging)
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
                if (bridging) {
                    bridging = false // keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        }, CAPTURE_TIMEOUT_MS)
        // Shizuku mode: the shell-side capture has no rate limit and takes
        // ~50 ms even on a waking panel. Falls back to the accessibility
        // screenshot if it fails.
        if (shellCapture()) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ShizukuBridge.capture(displayId, excludedLayers(), INITIAL_SHELL_SCALE)
                }
                if (stale()) return@launch
                if (bitmap == null) {
                    Log.i(TAG, "display $displayId: shell capture unavailable; using accessibility screenshot")
                    accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
                    return@launch
                }
                if (afterSwap && attempt < MAX_CAPTURE_ATTEMPTS) {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) return@launch
                    if (black && !demoRunning) {
                        Log.i(TAG, "display $displayId: shell capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, SHELL_RETRY_MS)
                        return@launch
                    }
                }
                onCaptured(bitmap, afterSwap, startTilt, t0)
            }
            return
        }
        accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
    }

    private fun shellCapture(): Boolean = DuoSettings.config.value.shizukuCapture && ShizukuBridge.ready

    /** Our own overlay's layer, so a capture taken while it's up sees the screen beneath it. */
    private fun excludedLayers(): List<SurfaceControl> {
        val view = (surface as? SnapshotSurface)?.view ?: return emptyList()
        return listOfNotNull(rootSurfaceControl(view))
    }

    private fun accessibilityCapture(
        gen: Int,
        attempt: Int,
        afterSwap: Boolean,
        startTilt: Float?,
        t0: Long,
        retry: () -> Unit,
        stale: () -> Boolean,
    ) {
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
                    if (bridging) bridging = false else phase = Phase.IDLE
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
                } else if (bridging) {
                    bridging = false // secure content etc.: keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        cache.put(innerPanel, bitmap)
        if (bridging) {
            bridging = false
            val s = surface as? SnapshotSurface
            if (s != null && phase == Phase.SHOWING) {
                Log.i(TAG, "display $displayId: fresh capture (${SystemClock.uptimeMillis() - t0}ms) replaces the bridge at tilt=${s.tilt}")
                s.replaceSnapshot(bitmap)
            }
            return
        }
        present(bitmap, afterSwap, startTilt, t0)
    }

    /**
     * Puts the effect on screen: [bitmap] through the shader, or with no
     * bitmap the live blur over whatever is there.
     */
    private fun present(bitmap: Bitmap?, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        if (phase != Phase.CAPTURING) {
            return
        }
        val angle = hinge.lastAngle
        val live = startTilt == null
        val coarse = live && hinge.isCoarse
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // A stops-only sensor can't be followed. Between stops the fold is in
        // motion, so: frost in on leaving rest and hold; on the fresh panel
        // start frosted and hold; clear only when the rest stop arrives (see
        // onHinge) or the hold cap expires.
        val tilt = startTilt ?: if (coarse) (if (afterSwap) peak else DuoShader.FLAT_EPSILON * 1.2f) else currentTilt()
        // A capture lands late; the live blur is instant, so it's never "too late".
        val nearlyDone = bitmap != null && afterSwap && live &&
            if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "display $displayId: hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            phase = Phase.IDLE
            return
        }
        // Closing onto a cover that only lights after the swap: the hinge HAL
        // goes quiet around 30°, so the overlay would never hear "closed".
        // Resolve on a timer instead — by the time this capture lands the
        // phone is shut anyway, so it reads as the cover settling into focus.
        // A cover lit alongside the inner panel keeps hearing the hinge.
        val easeTo = when {
            coarse -> peak
            afterSwap && !innerPanel && !concurrentCover() && live -> 0f
            else -> null
        }
        val how = if (bitmap != null) "${bitmap.width}x${bitmap.height} snapshot (capture ${SystemClock.uptimeMillis() - t0}ms)" else "live blur"
        Log.i(TAG, "display $displayId: showing $how at tilt=$tilt${easeTo?.let { " easing to $it" } ?: ""}${if (bitmap != null && shellCapture()) " [shizuku]" else ""}")
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
    private fun show(bitmap: Bitmap?, startTilt: Float, fadeIn: Boolean = false, easeTo: Float? = null) {
        val inner = innerPanel
        val config = DuoSettings.config.value
        val foldLine = { w: Float, h: Float, c: com.duoopen.settings.DuoConfig -> DuoShader.foldFor(inner, w, h, c) }
        val created: FoldSurface? = if (bitmap != null) {
            SnapshotSurface(service, windowManager, bitmap, config, foldLine).takeIf { it.attached }
        } else {
            LiveBlurSurface(service, windowManager, config, DuoShader.pxPerMm(service.createDisplayContext(display)), foldLine)
                .takeIf { it.attached }
        }
        if (created == null) {
            Log.e(TAG, "display $displayId: could not attach overlay")
            phase = Phase.IDLE
            return
        }
        created.tilt = startTilt
        surface = created
        if (bitmap != null && shellCapture()) startLiveLoop()
        phase = Phase.SHOWING
        onShowingChanged()
        if (fadeIn) {
            // Content was already live on this panel; ease the frost in.
            created.fadeIn(FADE_IN_MS)
        }
        follower = TiltFollower { t ->
            created.tilt = t
            if (t < DuoShader.FLAT_EPSILON && !demoRunning) dismiss(fadeMs = FADE_OUT_FLAT_MS)
        }.also { it.snap(startTilt) }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        timedResolve = easeTo != null
        if (easeTo != null) {
            follower?.tauS = if (hinge.isCoarse) COARSE_EASE_TAU_S else TIMED_RESOLVE_TAU_S
            follower?.setTarget(easeTo)
            if (easeTo > DuoShader.FLAT_EPSILON) {
                handler.postDelayed(peakHold, if (hinge.isCoarse) COARSE_PEAK_HOLD_MS else PEAK_HOLD_MS)
            }
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    private fun dismiss(fadeMs: Long) {
        val s = surface ?: return
        Log.i(TAG, "display $displayId: dismiss (fade ${fadeMs}ms) at tilt=${s.tilt}")
        clearOverlayState()
        s.fadeOut(fadeMs) { s.detach() }
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        timedResolve = false
        val s = surface ?: return
        clearOverlayState()
        s.detach()
    }

    /**
     * Shizuku mode: keep re-capturing the screen beneath the overlay at a
     * reduced scale, so the picture under the frost is live instead of frozen.
     */
    private fun startLiveLoop() {
        if (liveLoop) return
        liveLoop = true
        val myGen = captureGen
        var frames = 0
        fun tick() {
            if (!liveLoop || phase != Phase.SHOWING || myGen != captureGen) { liveLoop = false; return }
            val s = surface as? SnapshotSurface
            if (s == null || s.tilt < DuoShader.FLAT_EPSILON) { handler.postDelayed({ tick() }, LIVE_INTERVAL_MS); return }
            val excluded = excludedLayers()
            if (excluded.isEmpty()) { handler.postDelayed({ tick() }, LIVE_INTERVAL_MS); return }
            scope.launch {
                val frame = withContext(Dispatchers.IO) { ShizukuBridge.capture(displayId, excluded, LIVE_SHELL_SCALE) }
                if (liveLoop && phase == Phase.SHOWING && myGen == captureGen && frame != null && !bridging) {
                    (surface as? SnapshotSurface)?.replaceSnapshot(frame)
                    frames++
                    if (frames == 1 || frames % 25 == 0) Log.i(TAG, "display $displayId: live frame $frames (${frame.width}x${frame.height})")
                }
                handler.postDelayed({ tick() }, LIVE_INTERVAL_MS)
            }
        }
        handler.postDelayed({ tick() }, LIVE_INTERVAL_MS)
    }

    private fun clearOverlayState() {
        liveLoop = false
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(peakHold)
        follower?.cancel()
        follower = null
        surface = null
        timedResolve = false
        bridging = false
        phase = Phase.IDLE
        onShowingChanged()
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
        startEffect(afterSwap = false, startTilt = if (inner) peak else 0.06f)
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
        /** Slower ease for stops-only sensors, so a frost-in reads as a fold (≈ 450 ms). */
        const val COARSE_EASE_TAU_S = 0.12f
        /** Clear-out once a stops-only sensor reports the rest stop (≈ 300 ms). */
        const val COARSE_CLEAR_TAU_S = 0.08f
        /** How long a timed frost-up stays before it fades, absent a panel swap. */
        const val PEAK_HOLD_MS = 1_200L
        /** Stops-only sensors: hold the frost between stops, but not forever (flex mode). */
        const val COARSE_PEAK_HOLD_MS = 2_500L
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        const val SKIP_INNER_ABOVE_HINGE = 135f
        const val SKIP_COVER_BELOW_HINGE = 10f
        const val SETTLE_TIMEOUT_MS = 700L
        const val FADE_IN_MS = 140L
        const val FADE_OUT_FLAT_MS = 120L
        const val FADE_OUT_STALLED_MS = 300L
        /** Shizuku capture: full-size first frame, half-size live frames at ~12 fps. */
        const val INITIAL_SHELL_SCALE = 1f
        const val LIVE_SHELL_SCALE = 0.5f
        const val LIVE_INTERVAL_MS = 80L
        const val SHELL_RETRY_MS = 60L

        /**
         * The window's root layer (hidden `ViewRootImpl.getSurfaceControl`),
         * needed to exclude our overlay from a display capture. Null if the
         * hidden-API exemption isn't in place.
         */
        fun rootSurfaceControl(view: View): SurfaceControl? = runCatching {
            val root = view.rootView.parent ?: return null
            val sc = root.javaClass.getMethod("getSurfaceControl").invoke(root) as? SurfaceControl
            sc?.takeIf { it.isValid }
        }.getOrNull()
    }
}
