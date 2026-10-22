package com.duoopen.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.fold.gl.GlFoldRenderer
import com.duoopen.settings.DuoConfig
import com.duoopen.wallpaper.WallpaperImage

/**
 * The preview's wallpaper image through the OpenGL ES fold, for Android
 * 11/12 (no RenderEffect shader there). Unlike [foldEffect] on API 33+, which
 * warps the whole preview, this warps only the picture: the clock and cards
 * drawn on top stay crisp.
 *
 * @param fold Hinge placement in px of this view (it fills the window, so
 *   window coordinates); null centers it using [DuoConfig.foldSplitsLong].
 */
@Composable
fun GlFoldPreview(
    image: Bitmap,
    tilt: Float,
    config: DuoConfig,
    pxPerMm: Float,
    fold: FoldLine?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = remember { GlPreviewHost(context) }
    DisposableEffect(host) { onDispose { host.release() } }
    if (host.failed.value) {
        // GL didn't work out: the plain picture, as on a flat screen.
        Image(
            bitmap = remember(image) { image.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    AndroidView(
        factory = { host.createView(it) },
        modifier = modifier,
        update = { host.update(image, tilt, config, pxPerMm, fold) },
    )
}

/** Owns the renderer and feeds it from the composable's latest parameters. */
private class GlPreviewHost(context: Context) : TextureView.SurfaceTextureListener {
    val failed = mutableStateOf(false)
    private val renderer = GlFoldRenderer(context, "DuoGlPreview", foldScale = 2f) { failed.value = true }
    private val contentMatrix = Matrix()
    private var view: TextureView? = null
    private var image: Bitmap? = null
    private var tilt = 0f
    private var config: DuoConfig? = null
    private var pxPerMm = 0f
    private var fold: FoldLine? = null

    fun createView(context: Context): TextureView = TextureView(context).also {
        it.isOpaque = true
        it.surfaceTextureListener = this
        view = it
    }

    fun update(image: Bitmap, tilt: Float, config: DuoConfig, pxPerMm: Float, fold: FoldLine?) {
        if (image !== this.image) {
            this.image = image
            renderer.setImage(image)
            view?.let { updateContentMatrix(it.width, it.height) }
        }
        this.tilt = tilt
        this.config = config
        this.pxPerMm = pxPerMm
        this.fold = fold
        pushFrame()
    }

    fun release() = renderer.release()

    /** View px -> image px: the inverse of the center-crop the plain preview uses. */
    private fun updateContentMatrix(w: Int, h: Int) {
        val bmp = image ?: return
        if (w <= 0 || h <= 0) return
        val (scale, dx, dy) = WallpaperImage.centerCrop(bmp.width, bmp.height, w.toFloat(), h.toFloat())
        val crop = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        crop.invert(contentMatrix)
        renderer.setContentMatrix(contentMatrix)
    }

    private fun pushFrame() {
        val v = view ?: return
        val c = config ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 1f || h <= 1f) return
        val line = fold ?: DuoShader.centeredFold(w, h, c.foldSplitsLong)
        renderer.setUniforms(DuoShader.uniforms(w, h, tilt, c, pxPerMm, line))
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
}
