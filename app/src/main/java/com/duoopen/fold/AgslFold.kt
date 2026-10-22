package com.duoopen.fold

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.duoopen.R

/**
 * The fold shader as AGSL (res/raw/duo_unfold.agsl) on API 33+. Below 33
 * there is no RuntimeShader; the same maths runs as OpenGL ES instead
 * ([com.duoopen.fold.gl.GlFoldRenderer]). Keep every caller behind an
 * SDK_INT check so no class loaded on an older release references this one.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object AgslFold {
    private const val TAG = "DuoShader"

    @Volatile
    private var source: String? = null

    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.duo_unfold)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (e: Exception) {
            Log.e(TAG, "AGSL compile failed: ${e.message}", e)
            null
        }
    }

    /** Sets every uniform except the `content` input, which each renderer binds itself. */
    fun apply(shader: RuntimeShader, u: FoldUniforms) {
        shader.setFloatUniform("resolution", u.width, u.height)
        shader.setFloatUniform("tiltDegrees", u.tiltDegrees)
        shader.setFloatUniform("eyeDistancePx", u.eyeDistancePx)
        shader.setFloatUniform("hingePos", u.hingePos)
        shader.setFloatUniform("eyeX", u.eyeX)
        shader.setFloatUniform("axisSwap", u.axisSwap)
        shader.setFloatUniform("paneSide", u.paneSide)
        shader.setFloatUniform("blurSpread", u.blurSpread)
        shader.setFloatUniform("darkening", u.darkening)
    }
}
