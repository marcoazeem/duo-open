package com.duoopen.fold.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.duoopen.fold.FoldUniforms

/**
 * res/raw/duo_unfold_gles.frag compiled and linked, plus the one texture it
 * samples. Needs a current ES 3.0 context; not thread-safe.
 */
internal class FoldProgram(fragmentSource: String) {
    private val program: Int
    private var texture = 0
    private var contentWidth = 0
    private var contentHeight = 0

    private val uContent: Int
    private val uContentMatrix: Int
    private val uContentSize: Int
    private val uResolution: Int
    private val uTilt: Int
    private val uEyeDistance: Int
    private val uHingePos: Int
    private val uEyeX: Int
    private val uAxisSwap: Int
    private val uPaneSide: Int
    private val uBlurSpread: Int
    private val uDarkening: Int

    val hasImage: Boolean get() = texture != 0 && contentWidth > 0 && contentHeight > 0

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("fold program link failed: $log")
        }
        uContent = GLES30.glGetUniformLocation(program, "content")
        uContentMatrix = GLES30.glGetUniformLocation(program, "contentMatrix")
        uContentSize = GLES30.glGetUniformLocation(program, "contentSize")
        uResolution = GLES30.glGetUniformLocation(program, "resolution")
        uTilt = GLES30.glGetUniformLocation(program, "tiltDegrees")
        uEyeDistance = GLES30.glGetUniformLocation(program, "eyeDistancePx")
        uHingePos = GLES30.glGetUniformLocation(program, "hingePos")
        uEyeX = GLES30.glGetUniformLocation(program, "eyeX")
        uAxisSwap = GLES30.glGetUniformLocation(program, "axisSwap")
        uPaneSide = GLES30.glGetUniformLocation(program, "paneSide")
        uBlurSpread = GLES30.glGetUniformLocation(program, "blurSpread")
        uDarkening = GLES30.glGetUniformLocation(program, "darkening")
    }

    /** Uploads [bitmap] (software config; see [GlFoldRenderer] for hardware ones). */
    fun upload(bitmap: Bitmap) {
        if (texture == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        contentWidth = bitmap.width
        contentHeight = bitmap.height
    }

    /**
     * Draws a full-viewport pass. [contentMatrix] maps render px to content px,
     * as 9 floats in android.graphics.Matrix (row-major) order.
     */
    fun draw(u: FoldUniforms, contentMatrix: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uContent, 0)
        // Row-major in, so transpose: allowed for glUniformMatrix* in ES 3.0.
        GLES30.glUniformMatrix3fv(uContentMatrix, 1, true, contentMatrix, 0)
        GLES30.glUniform2f(uContentSize, contentWidth.toFloat(), contentHeight.toFloat())
        GLES30.glUniform2f(uResolution, u.width, u.height)
        GLES30.glUniform1f(uTilt, u.tiltDegrees)
        GLES30.glUniform1f(uEyeDistance, u.eyeDistancePx)
        GLES30.glUniform1f(uHingePos, u.hingePos)
        GLES30.glUniform1f(uEyeX, u.eyeX)
        GLES30.glUniform1f(uAxisSwap, u.axisSwap)
        GLES30.glUniform1f(uPaneSide, u.paneSide)
        GLES30.glUniform1f(uBlurSpread, u.blurSpread)
        GLES30.glUniform1f(uDarkening, u.darkening)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        GLES30.glDeleteProgram(program)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("fold shader compile failed: $log")
        }
        return shader
    }

    private companion object {
        /** One triangle that covers the viewport; no vertex buffers needed. */
        const val VERTEX_SHADER = """#version 300 es
const vec2 POS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
    gl_Position = vec4(POS[gl_VertexID], 0.0, 1.0);
}
"""
    }
}
