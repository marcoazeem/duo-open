package com.duoopen.fold.gl

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.duoopen.R
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldUniforms
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The fold shader on OpenGL ES 3.0 (res/raw/duo_unfold_gles.frag), for
 * Android 11/12 where AGSL RuntimeShader doesn't exist. Renders into a window
 * surface — a TextureView's SurfaceTexture (overlay, in-app preview) or a
 * wallpaper Surface — on its own thread.
 *
 * Every public method may be called from the main thread. Inputs are "latest
 * wins": [setImage], [setContentMatrix] and [setUniforms] only record state,
 * and [requestRender] draws whatever is current, coalescing requests that
 * arrive while a frame is pending. With no image (or no uniforms) yet, it
 * clears to black.
 *
 * @param foldScale While the fold is visible (tilt above
 *   [DuoShader.FLAT_EPSILON]), render it at 1/foldScale resolution into an
 *   offscreen buffer and scale that up — the frost hides the upscale, and the
 *   32-tap shader at full resolution is too heavy for older GPUs. Flat frames
 *   always draw at full resolution. 1 = always full resolution.
 * @param onFailure Called on the main thread if EGL or the shader fails;
 *   the renderer then releases its surface and draws nothing more, so the
 *   caller can fall back to the plain picture.
 */
class GlFoldRenderer(
    context: Context,
    name: String,
    private val foldScale: Float = 1f,
    private val onFailure: (() -> Unit)? = null,
) {
    private val resources = context.applicationContext.resources
    private val thread = HandlerThread(name).also { it.start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Shared with the caller's thread, guarded by `lock`.
    private val lock = Any()
    private var pendingImage: Bitmap? = null
    private var uniforms: FoldUniforms? = null
    private val matrixValues = FloatArray(9).also { Matrix().getValues(it) }

    private val renderQueued = AtomicBoolean(false)
    @Volatile
    private var released = false

    /** True once EGL or the shader has failed; nothing will be drawn. */
    @Volatile
    var failed = false
        private set

    // GL thread only.
    private var egl: EglCore? = null
    private var program: FoldProgram? = null
    private var surface: EGLSurface? = null
    private var width = 0
    private var height = 0
    private val drawMatrix = FloatArray(9)
    private val scaledMatrix = FloatArray(9)
    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private val renderTask = Runnable {
        renderQueued.set(false)
        render()
    }

    /** Starts drawing into [target] (a Surface or SurfaceTexture) of [width] x [height] px. */
    fun attach(target: Any, width: Int, height: Int) {
        if (released || failed) return
        handler.post { doAttach(target, width, height) }
    }

    fun resize(width: Int, height: Int) {
        if (released) return
        handler.post {
            this.width = width
            this.height = height
            render()
        }
    }

    /**
     * Stops drawing into the current surface and releases it. Blocks until
     * the GL thread has let go, so the caller may destroy the surface next.
     */
    fun detach() {
        if (released) return
        runOnGlThread { releaseSurface() }
    }

    /**
     * The picture to warp. A [Bitmap.Config.HARDWARE] bitmap (a screenshot)
     * is copied to a software one on the GL thread for the upload. The
     * caller must not recycle [bitmap] while it may still be pending.
     */
    fun setImage(bitmap: Bitmap) {
        synchronized(lock) { pendingImage = bitmap }
    }

    /** Maps render px to image px; the inverse of a BitmapShader local matrix. */
    fun setContentMatrix(matrix: Matrix) {
        synchronized(lock) { matrix.getValues(matrixValues) }
    }

    fun setUniforms(u: FoldUniforms) {
        synchronized(lock) { uniforms = u }
    }

    fun requestRender() {
        if (released || failed) return
        if (renderQueued.compareAndSet(false, true)) handler.post(renderTask)
    }

    /** Tears everything down and ends the GL thread. Blocks until done; idempotent. */
    fun release() {
        if (released) return
        released = true
        runOnGlThread {
            runCatching {
                releaseSurface()
                egl?.release()
            }.onFailure { Log.w(TAG, "release", it) }
            egl = null
            program = null
            fbo = 0
            fboTexture = 0
            synchronized(lock) { pendingImage = null }
        }
        thread.quitSafely()
    }

    private fun doAttach(target: Any, width: Int, height: Int) {
        if (failed) return
        try {
            val e = egl ?: EglCore().also { egl = it }
            releaseSurface()
            val s = e.createWindowSurface(target)
            surface = s
            this.width = width
            this.height = height
            check(e.makeCurrent(s)) { "eglMakeCurrent: ${e.errorString()}" }
            if (program == null) program = FoldProgram(fragmentSource(resources))
            render()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun render() {
        if (failed) return
        val s = surface ?: return
        val e = egl ?: return
        val p = program ?: return
        if (width <= 0 || height <= 0) return
        val image: Bitmap?
        val u: FoldUniforms?
        synchronized(lock) {
            image = pendingImage
            pendingImage = null
            u = uniforms
            System.arraycopy(matrixValues, 0, drawMatrix, 0, 9)
        }
        try {
            if (!e.makeCurrent(s)) {
                Log.w(TAG, "eglMakeCurrent failed: ${e.errorString()}")
                return
            }
            if (image != null) upload(p, image)
            if (p.hasImage && u != null && foldScale > 1f && u.tiltDegrees >= DuoShader.FLAT_EPSILON) {
                drawReduced(p, u)
            } else if (p.hasImage && u != null) {
                GLES30.glViewport(0, 0, width, height)
                p.draw(u, drawMatrix)
            } else {
                GLES30.glViewport(0, 0, width, height)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            if (!e.swap(s)) Log.w(TAG, "eglSwapBuffers failed: ${e.errorString()}")
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** The fold pass at 1/[foldScale] size into [fbo], then scaled up onto the window. */
    private fun drawReduced(p: FoldProgram, u: FoldUniforms) {
        val w = ceil(width / foldScale).toInt()
        val h = ceil(height / foldScale).toInt()
        ensureFbo(w, h)
        // Render px of the small target -> window px -> content px.
        System.arraycopy(drawMatrix, 0, scaledMatrix, 0, 9)
        for (i in intArrayOf(0, 1, 3, 4, 6, 7)) scaledMatrix[i] *= foldScale
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        p.draw(u.scaledBy(1f / foldScale), scaledMatrix)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            0, 0, w, h,
            0, 0, (w * foldScale).roundToInt(), (h * foldScale).roundToInt(),
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboWidth == w && fboHeight == h) return
        if (fbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES30.glGenTextures(1, ids, 0)
            fboTexture = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "fold FBO incomplete: 0x${Integer.toHexString(status)}" }
        fboWidth = w
        fboHeight = h
    }

    private fun upload(p: FoldProgram, image: Bitmap) {
        if (image.isRecycled) return
        val soft = if (image.config == Bitmap.Config.HARDWARE) {
            image.copy(Bitmap.Config.ARGB_8888, false) ?: return
        } else {
            image
        }
        try {
            p.upload(soft)
        } finally {
            if (soft !== image) soft.recycle()
        }
    }

    private fun releaseSurface() {
        val s = surface ?: return
        surface = null
        runCatching { egl?.releaseSurface(s) }.onFailure { Log.w(TAG, "releaseSurface", it) }
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "GL fold renderer failed; drawing nothing from here on", t)
        failed = true
        releaseSurface()
        onFailure?.let { mainHandler.post(it) }
    }

    private fun runOnGlThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (posted && !done.await(BLOCKING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "GL thread busy; stopped waiting after ${BLOCKING_TIMEOUT_MS}ms")
        }
    }

    private companion object {
        const val TAG = "DuoGl"
        const val BLOCKING_TIMEOUT_MS = 500L

        @Volatile
        private var source: String? = null

        fun fragmentSource(resources: Resources): String =
            source ?: resources.openRawResource(R.raw.duo_unfold_gles)
                .bufferedReader().use { it.readText() }
                .also { source = it }
    }
}
