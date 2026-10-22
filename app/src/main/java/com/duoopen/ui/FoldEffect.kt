package com.duoopen.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.duoopen.fold.AgslFold
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig

/**
 * Applies the two-pane Duo fold to this layout subtree. [tilt] is read inside
 * the layer block, so a moving hinge only re-runs the layer, not composition.
 * API 33+ only; below that the preview warps just the wallpaper image through
 * OpenGL ES ([GlFoldPreview]).
 *
 * @param fold Hinge placement in layer px; null centers it using
 *   [DuoConfig.foldSplitsLong].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.foldEffect(
    shader: RuntimeShader,
    tilt: () -> Float,
    config: DuoConfig,
    pxPerMm: Float,
    fold: FoldLine?,
): Modifier = graphicsLayer {
    val t = tilt()
    val w = size.width
    val h = size.height
    if (t < DuoShader.FLAT_EPSILON || w <= 1f || h <= 1f) {
        renderEffect = null
        return@graphicsLayer
    }
    val line = fold ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
    AgslFold.apply(shader, DuoShader.uniforms(w, h, t, config, pxPerMm, line))
    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    clip = true
}
