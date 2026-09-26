package com.duoopen.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlin.math.ceil

/**
 * Full-screen overlay that draws a frozen screenshot through the fold shader.
 *
 * The shader runs per output pixel with up to 32 taps, which is too much for a
 * 5.5 MP panel at 120 Hz. So the shader view is laid out at 1/[renderScale]
 * size, rendered into its own hardware layer, and scaled back up; the frost
 * hides the upscale, and at tilt 0 the plain snapshot is drawn 1:1 instead.
 */
class FoldOverlayView(
    context: Context,
    snapshot: Bitmap,
    /** Hinge geometry for this panel; null = centered from the config. */
    foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)? = null,
    private val renderScale: Float = 2f,
) : ViewGroup(context) {

    private val fold = FoldView(context, snapshot, renderScale, foldLine)
    private val flat = FlatView(context, snapshot)

    var tilt: Float
        get() = fold.tilt
        set(value) {
            fold.tilt = value
            val useFold = value >= DuoShader.FLAT_EPSILON
            fold.visibility = if (useFold) VISIBLE else INVISIBLE
            flat.visibility = if (useFold) INVISIBLE else VISIBLE
        }

    var config: DuoConfig
        get() = fold.config
        set(value) {
            fold.config = value
        }

    /** Swaps the picture under the effect in place (e.g. a stale bridge → the fresh capture). */
    fun setSnapshot(bitmap: Bitmap) {
        fold.setSnapshot(bitmap)
        flat.setSnapshot(bitmap)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(flat)
        addView(fold)
        fold.pivotX = 0f
        fold.pivotY = 0f
        fold.scaleX = renderScale
        fold.scaleY = renderScale
        fold.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        fold.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        flat.layout(0, 0, r - l, b - t)
        fold.layout(0, 0, fold.measuredWidth, fold.measuredHeight)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /** Snapshot drawn 1:1 — pixel-identical to the live screen underneath. */
    private class FlatView(context: Context, private var snapshot: Bitmap) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val matrix = Matrix()

        fun setSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** Snapshot through the fold shader, at reduced resolution. */
    private class FoldView(
        context: Context,
        private var snapshot: Bitmap,
        renderScale: Float,
        private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
    ) : View(context) {
        private val shader: RuntimeShader? = DuoShader.create(context)
        private val pxPerMm = DuoShader.pxPerMm(context) / renderScale
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var image = BitmapShader(snapshot, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        private val matrix = Matrix()

        fun setSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            image = BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
            if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

        var config: DuoConfig = DuoSettings.config.value

        var tilt = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            image.setLocalMatrix(matrix)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            canvas.drawColor(Color.BLACK)
            val fold = shader
            if (fold == null) {
                paint.shader = image
            } else {
                val line = foldLine?.invoke(w, h, config) ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
                DuoShader.setUniforms(fold, w, h, tilt, config, pxPerMm, line)
                fold.setInputShader("content", image)
                paint.shader = fold
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}
