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
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import com.duoopen.fold.AgslFold
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.fold.gl.GlFoldRenderer
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlin.math.ceil

/**
 * Full-screen overlay that draws a frozen screenshot through the fold shader.
 *
 * The shader runs per output pixel with up to 32 taps, which is too much for a
 * 5.5 MP panel at 120 Hz. So the shader view is laid out at 1/[renderScale]
 * size, rendered into its own texture, and scaled back up; the frost hides the
 * upscale, and at tilt 0 the plain snapshot is drawn 1:1 instead.
 *
 * The shader view is AGSL on API 33+ ([AgslFoldView]) and OpenGL ES below
 * ([GlFoldView]); if neither can draw, the flat snapshot stays up.
 */
class FoldOverlayView(
    context: Context,
    snapshot: Bitmap,
    /** Hinge geometry for this panel; null = centered from the config. */
    foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)? = null,
    private val renderScale: Float = 2f,
) : ViewGroup(context) {

    private val fold: FoldLayer? = createFoldLayer(context, snapshot, renderScale, foldLine) { tilt = tilt }
    private val flat = FlatView(context, snapshot)

    var tilt: Float = 0f
        set(value) {
            field = value
            val layer = fold ?: return
            layer.tilt = value
            val useFold = value >= DuoShader.FLAT_EPSILON && !layer.failed
            layer.view.visibility = if (useFold) VISIBLE else INVISIBLE
            flat.visibility = if (useFold) INVISIBLE else VISIBLE
        }

    var config: DuoConfig = DuoSettings.config.value
        set(value) {
            field = value
            fold?.config = value
        }

    /** Swaps the picture under the effect in place (e.g. a stale bridge → the fresh capture). */
    fun setSnapshot(bitmap: Bitmap) {
        fold?.setSnapshot(bitmap)
        flat.setSnapshot(bitmap)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(flat)
        fold?.view?.let { v ->
            addView(v)
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = renderScale
            v.scaleY = renderScale
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        fold?.view?.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        flat.layout(0, 0, r - l, b - t)
        fold?.view?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
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
}

/** The reduced-resolution view that draws the snapshot through the fold shader. */
internal interface FoldLayer {
    val view: View
    var tilt: Float
    var config: DuoConfig
    /** True when this layer can't draw (GL failed); the flat snapshot shows instead. */
    val failed: Boolean
    fun setSnapshot(bitmap: Bitmap)
}

private fun createFoldLayer(
    context: Context,
    snapshot: Bitmap,
    renderScale: Float,
    foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
    onFailed: () -> Unit,
): FoldLayer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslFoldView.create(context, snapshot, renderScale, foldLine)
    } else {
        GlFoldView(context, snapshot, renderScale, foldLine, onFailed)
    }

/**
 * Snapshot through the OpenGL ES port of the shader (Android 11/12), at
 * reduced resolution: the TextureView's buffer is the view's (already
 * reduced) size and the parent scales the view back up.
 */
private class GlFoldView(
    context: Context,
    private var snapshot: Bitmap,
    renderScale: Float,
    private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
    onFailed: () -> Unit,
) : TextureView(context), FoldLayer, TextureView.SurfaceTextureListener {
    private val pxPerMm = DuoShader.pxPerMm(context) / renderScale
    private val renderer = GlFoldRenderer(context, "DuoGlOverlay", onFailure = onFailed)
    private val contentMatrix = Matrix()

    init {
        isOpaque = true
        surfaceTextureListener = this
        renderer.setImage(snapshot)
    }

    override val view: View get() = this
    override val failed: Boolean get() = renderer.failed

    override var config: DuoConfig = DuoSettings.config.value
        set(value) {
            field = value
            pushFrame()
        }

    override var tilt = 0f
        set(value) {
            if (field != value) {
                field = value
                pushFrame()
            }
        }

    override fun setSnapshot(bitmap: Bitmap) {
        snapshot = bitmap
        renderer.setImage(bitmap)
        updateContentMatrix(width, height)
        pushFrame()
    }

    /** Render px -> snapshot px: the snapshot is stretched over the view, like FlatView. */
    private fun updateContentMatrix(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        contentMatrix.setScale(snapshot.width / w.toFloat(), snapshot.height / h.toFloat())
        renderer.setContentMatrix(contentMatrix)
    }

    private fun pushFrame() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return
        val line = foldLine?.invoke(w, h, config) ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
        renderer.setUniforms(DuoShader.uniforms(w, h, tilt, config, pxPerMm, line))
        renderer.requestRender()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        updateContentMatrix(width, height)
        renderer.attach(surface, width, height)
        pushFrame()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateContentMatrix(width, height)
        renderer.resize(width, height)
        pushFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer.detach()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.release()
    }
}

/** Snapshot through the AGSL fold shader, at reduced resolution, in a hardware layer. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslFoldView(
    context: Context,
    private var snapshot: Bitmap,
    private val shader: RuntimeShader,
    renderScale: Float,
    private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
) : View(context), FoldLayer {
    private val pxPerMm = DuoShader.pxPerMm(context) / renderScale
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var image = BitmapShader(snapshot, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
    private val matrix = Matrix()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override val view: View get() = this
    override val failed: Boolean get() = false

    override fun setSnapshot(bitmap: Bitmap) {
        snapshot = bitmap
        image = BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    override var config: DuoConfig = DuoSettings.config.value

    override var tilt = 0f
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
        val line = foldLine?.invoke(w, h, config) ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
        AgslFold.apply(shader, DuoShader.uniforms(w, h, tilt, config, pxPerMm, line))
        shader.setInputShader("content", image)
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    companion object {
        /** Null when the AGSL doesn't compile; the overlay then shows the flat snapshot only. */
        fun create(
            context: Context,
            snapshot: Bitmap,
            renderScale: Float,
            foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
        ): AgslFoldView? {
            val shader = AgslFold.create(context) ?: return null
            return AgslFoldView(context, snapshot, shader, renderScale, foldLine)
        }
    }
}
