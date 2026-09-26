package com.duoopen.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What a [PanelEngine] drives: something on screen whose frost follows a
 * pane tilt. Two implementations — a warped screenshot ([SnapshotSurface])
 * and the system's own window blur over the live screen ([LiveBlurSurface]).
 */
interface FoldSurface {
    var tilt: Float
    fun fadeIn(durationMs: Long)
    fun fadeOut(durationMs: Long, onEnd: () -> Unit)
    fun detach()
}

private fun overlayParams(width: Int, height: Int, format: Int, extraFlags: Int = 0) =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or extraFlags,
        format,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "DuoOpenFold"
    }

/** One screenshot drawn through the shader; frost, darkening and perspective per pixel. */
class SnapshotSurface(
    context: Context,
    private val windowManager: WindowManager,
    bitmap: Bitmap,
    config: DuoConfig,
    foldLine: (w: Float, h: Float, config: DuoConfig) -> FoldLine,
) : FoldSurface {
    val view = FoldOverlayView(context, bitmap, foldLine).also { it.config = config }
    val attached: Boolean

    init {
        attached = runCatching {
            windowManager.addView(
                view,
                overlayParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    PixelFormat.OPAQUE,
                ),
            )
        }.onFailure { Log.e(TAG, "addView failed", it) }.isSuccess
    }

    override var tilt: Float
        get() = view.tilt
        set(value) {
            view.tilt = value
        }

    /** Swap a stale bridging picture for the fresh capture, keeping the current tilt. */
    fun replaceSnapshot(bitmap: Bitmap) = view.setSnapshot(bitmap)

    override fun fadeIn(durationMs: Long) {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(durationMs).start()
    }

    override fun fadeOut(durationMs: Long, onEnd: () -> Unit) {
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    override fun detach() {
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private companion object {
        const val TAG = "DuoOverlay"
    }
}

/**
 * The system's cross-window blur over the live screen: no screenshot, no
 * capture delay, content keeps moving underneath. SurfaceFlinger blurs a
 * whole window by one radius, so the frost gradient is approximated with
 * [STRIPS] strip windows from the crease outward, each blurred by the radius
 * the shader would use at its centre, and darkened with a gradient painted
 * on top. Perspective isn't reproduced.
 */
class LiveBlurSurface(
    context: Context,
    private val windowManager: WindowManager,
    private val config: DuoConfig,
    pxPerMm: Float,
    foldLine: (w: Float, h: Float, config: DuoConfig) -> FoldLine,
) : FoldSurface {

    private class Strip(val view: StripView, val params: WindowManager.LayoutParams, val dNear: Float, val dFar: Float) {
        var radius = -1
    }

    private val strips = ArrayList<Strip>()
    private val darkPerPx = config.darkening * REFERENCE_PX_PER_MM / pxPerMm
    private var fade: ValueAnimator? = null
    /** 0..1 multiplier used by fade-in/out so a fade reads as the frost easing, not a popping window. */
    private var fadeScale = 1f
    val attached: Boolean

    init {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val fold = foldLine(w, h, config)
        val moving = fold.movingSide ?: config.movingSide
        // Work along the split axis: `len` is the screen extent across the hinge.
        val len = if (fold.splitsX) w else h
        val regions = ArrayList<Pair<Float, Float>>() // (from, to) along the axis, hinge-relative order irrelevant
        if (moving <= 0) regions += 0f to fold.position           // left / top pane
        if (moving >= 0) regions += fold.position to len          // right / bottom pane
        val perRegion = if (regions.size == 2) STRIPS / 2 else STRIPS
        var ok = true
        for ((from, to) in regions) {
            val span = to - from
            if (span < 1f) continue
            val step = span / perRegion
            for (i in 0 until perRegion) {
                val a = from + i * step
                val b = if (i == perRegion - 1) to else from + (i + 1) * step
                // Distance from the hinge at each edge of the strip.
                val dA = kotlin.math.abs(a - fold.position)
                val dB = kotlin.math.abs(b - fold.position)
                val (near, far) = if (dA <= dB) dA to dB else dB to dA
                val nearIsStart = dA <= dB
                val x = if (fold.splitsX) a.toInt() else 0
                val y = if (fold.splitsX) 0 else a.toInt()
                val sw = if (fold.splitsX) ceil(b - a).toInt() else w.toInt()
                val sh = if (fold.splitsX) h.toInt() else ceil(b - a).toInt()
                val view = StripView(context, horizontal = fold.splitsX, darkAtStart = nearIsStart)
                val params = overlayParams(sw, sh, PixelFormat.TRANSLUCENT, WindowManager.LayoutParams.FLAG_BLUR_BEHIND).apply {
                    this.x = x
                    this.y = y
                    blurBehindRadius = 0
                }
                val added = runCatching { windowManager.addView(view, params) }
                    .onFailure { Log.e(TAG, "strip addView failed", it) }.isSuccess
                if (!added) { ok = false; break }
                strips += Strip(view, params, near, far)
            }
        }
        attached = ok && strips.isNotEmpty()
        if (!attached) detach()
    }

    override var tilt: Float = 0f
        set(value) {
            field = value
            apply()
        }

    private fun apply() {
        val s = sin(Math.toRadians((tilt.coerceIn(0f, DuoShader.MAX_TILT)).toDouble())).toFloat() * fadeScale
        for (strip in strips) {
            // Same law as the shader: radius = blurSpread * gap, gap = d * sin(tilt).
            val rNear = config.blurSpread * strip.dNear * s
            val rFar = config.blurSpread * strip.dFar * s
            val radius = (((rNear + rFar) * 0.5f).coerceIn(0f, MAX_BLUR_PX) / RADIUS_STEP).roundToInt() * RADIUS_STEP.toInt()
            strip.view.setDarkening(
                (darkPerPx * rNear).coerceIn(0f, MAX_DARK),
                (darkPerPx * rFar).coerceIn(0f, MAX_DARK),
            )
            if (radius != strip.radius) {
                strip.radius = radius
                strip.params.blurBehindRadius = radius
                runCatching { windowManager.updateViewLayout(strip.view, strip.params) }
            }
        }
    }

    override fun fadeIn(durationMs: Long) = animateScale(0f, 1f, durationMs, null)

    override fun fadeOut(durationMs: Long, onEnd: () -> Unit) = animateScale(fadeScale, 0f, durationMs, onEnd)

    private fun animateScale(from: Float, to: Float, durationMs: Long, onEnd: (() -> Unit)?) {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fadeScale = it.animatedValue as Float
                apply()
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
            }
            start()
        }
    }

    override fun detach() {
        fade?.cancel()
        for (strip in strips) runCatching { windowManager.removeViewImmediate(strip.view) }
        strips.clear()
    }

    /** Translucent black gradient along the strip: darker the farther from the crease. */
    private class StripView(context: Context, private val horizontal: Boolean, private val darkAtStart: Boolean) : View(context) {
        private val paint = Paint()
        private var nearAlpha = 0f
        private var farAlpha = 0f

        fun setDarkening(near: Float, far: Float) {
            if (near == nearAlpha && far == farAlpha) return
            nearAlpha = near
            farAlpha = far
            rebuild()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

        private fun rebuild() {
            if (width == 0 || height == 0) return
            val start = if (darkAtStart) nearAlpha else farAlpha
            val end = if (darkAtStart) farAlpha else nearAlpha
            val c0 = Color.argb((start * 255).roundToInt(), 0, 0, 0)
            val c1 = Color.argb((end * 255).roundToInt(), 0, 0, 0)
            paint.shader = if (horizontal) {
                LinearGradient(0f, 0f, width.toFloat(), 0f, c0, c1, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, 0f, 0f, height.toFloat(), c0, c1, Shader.TileMode.CLAMP)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (paint.shader == null) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        const val TAG = "DuoOverlay"
        const val STRIPS = 6
        /** SurfaceFlinger blur gets expensive and flat beyond this. */
        const val MAX_BLUR_PX = 90f
        /** Relayout only when the radius moves by this much (each update is a WindowManager round-trip). */
        const val RADIUS_STEP = 4f
        const val MAX_DARK = 0.85f
        const val REFERENCE_PX_PER_MM = 6f
    }
}
