package com.duoopen.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.duoopen.fold.AgslFold
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.gl.GlFoldRenderer
import com.duoopen.fold.isInnerPanel
import com.duoopen.overlay.OverlayState
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live wallpaper that plays the Duo frosted-glass fold whenever the inner
 * display is partly folded: the wallpaper image sits on the flat plane, the
 * moving half of the screen acts as a glass pane hinged at the crease, and the
 * image settles into focus as the hinge reaches flat.
 *
 * Draws only on hinge movement / surface changes (no idle frames). On the
 * cover screen (tall, narrow) the image is drawn plain, and while the
 * full-screen overlay is running it steps aside so nothing is frosted twice.
 *
 * API 33+ draws through a hardware Canvas with the AGSL shader. Below 33 the
 * OpenGL ES port ([GlFoldRenderer]) owns the surface and draws every frame,
 * flat ones included — a Surface takes one producer at a time, so the two
 * can't be mixed. If GL fails, the plain image is drawn through a Canvas.
 */
class DuoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DuoEngine()

    private inner class DuoEngine : Engine() {
        private val scope = MainScope()
        private val context = this@DuoWallpaperService
        private val hinge = HingeAngleSource(context, ::onHingeAngle)
        private val foldShader: RuntimeShader? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslFold.create(context) else null
        private val gl: GlFoldRenderer? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                GlFoldRenderer(context, "DuoGlWallpaper", foldScale = GL_FOLD_SCALE, onFailure = ::onGlFailed)
            } else {
                null
            }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val follower = TiltFollower { draw() }

        private var config: DuoConfig = DuoSettings.config.value
        private var bitmap: Bitmap? = null
        private var bitmapVersion = -1L
        private var imageShader: BitmapShader? = null
        private var pxPerMm = DuoShader.pxPerMm(context)

        private var surfaceReady = false
        private var width = 0f
        private var height = 0f
        /** The GL renderer is drawing into this engine's surface. */
        private var glAttached = false
        /** The bitmap last handed to the GL renderer, so a resize doesn't re-upload it. */
        private var glImage: Bitmap? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            // Listen for the engine's whole life, not just while visible: the
            // OnePlus Open's hinge HAL sends nothing on registration, so a
            // listener started at unfold time would miss a fast open entirely.
            // It's on-change, so it's silent unless the hinge actually moves.
            hinge.start()
            scope.launch {
                DuoSettings.config.collect { c ->
                    config = c
                    if (c.imageVersion != bitmapVersion) loadImage(c.imageVersion)
                    follower.snap(tiltFor(hinge.lastAngle))
                    draw()
                }
            }
            scope.launch { OverlayState.running.collect { draw() } }
        }

        override fun onDestroy() {
            hinge.stop()
            follower.cancel()
            scope.cancel()
            gl?.release()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                follower.snap(follower.target)
                draw()
            } else {
                follower.cancel()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            surfaceReady = true
            width = w.toFloat()
            height = h.toFloat()
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            gl?.takeUnless { it.failed }?.let { g ->
                if (glAttached) {
                    g.resize(w, h)
                } else {
                    g.attach(holder.surface, w, h)
                    glAttached = true
                }
            }
            rebuildImageShader()
            // Folded -> unfolding swaps panels: start from the current hinge
            // pose instead of animating in from a stale value.
            follower.snap(follower.target)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            follower.cancel()
            if (glAttached) {
                gl?.detach() // blocks until GL has let go of the surface
                glAttached = false
            }
            super.onSurfaceDestroyed(holder)
        }

        /** GL broke and released the surface: fall back to the plain image on a Canvas. */
        private fun onGlFailed() {
            glAttached = false
            rebuildImageShader()
            draw()
        }

        private fun onHingeAngle(angle: Float) {
            val tilt = tiltFor(angle)
            if (isInner() && isVisible && surfaceReady) follower.setTarget(tilt) else follower.snap(tilt)
        }

        private fun tiltFor(angle: Float): Float =
            if (angle.isNaN()) 0f else DuoShader.tiltForHinge(angle, config)

        private fun isInner(): Boolean = displayContext?.display.isInnerPanel(context)

        private fun loadImage(version: Long) {
            bitmapVersion = version
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { WallpaperImage.load(context, version) }
                if (version != bitmapVersion) return@launch
                bitmap = bmp
                rebuildImageShader()
                draw()
            }
        }

        private fun rebuildImageShader() {
            val bmp = bitmap ?: return
            if (width <= 0f || height <= 0f) return
            val (scale, dx, dy) = WallpaperImage.centerCrop(bmp.width, bmp.height, width, height)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val g = gl
            if (g != null && glAttached) {
                if (glImage !== bmp) {
                    g.setImage(bmp)
                    glImage = bmp
                }
                // The GL shader samples the other way round: surface px -> image px.
                g.setContentMatrix(Matrix().also { matrix.invert(it) })
                return
            }
            // DECAL: samples outside the image are transparent, so the frost
            // fades to black at the edges like the original instead of smearing.
            imageShader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                BitmapShader(bmp, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                    setLocalMatrix(matrix)
                }
            } else {
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            }
        }

        private fun currentTilt(): Float =
            if (isInner() && !OverlayState.running.value) follower.current else 0f

        private fun draw() {
            if (!surfaceReady) return
            val g = gl
            if (g != null && glAttached) {
                drawGl(g)
                return
            }
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "lockHardwareCanvas failed", e)
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val image = imageShader ?: return
                val shader = foldShader
                val tilt = currentTilt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null && tilt >= DuoShader.FLAT_EPSILON) {
                    val fold = DuoShader.centeredFold(width, height, config.foldSplitsLong)
                    AgslFold.apply(shader, DuoShader.uniforms(width, height, tilt, config, pxPerMm, fold))
                    shader.setInputShader("content", image)
                    paint.shader = shader
                } else {
                    paint.shader = image
                }
                canvas.drawRect(0f, 0f, width, height, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        /** Below API 33: every frame through GL; tilt 0 is a plain pass-through. */
        private fun drawGl(g: GlFoldRenderer) {
            val fold = DuoShader.centeredFold(width, height, config.foldSplitsLong)
            g.setUniforms(DuoShader.uniforms(width, height, currentTilt(), config, pxPerMm, fold))
            g.requestRender()
        }
    }

    private companion object {
        const val TAG = "DuoWallpaper"
        /** GL fold frames render at half resolution and are scaled up (flat frames stay full). */
        const val GL_FOLD_SCALE = 2f
    }
}
