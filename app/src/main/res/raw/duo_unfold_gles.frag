#version 300 es
// Duo-Unfold for OpenGL ES 3.0: a line-by-line port of duo_unfold.agsl for
// Android 11/12 (API 30-32), which have no RuntimeShader. KEEP THE TWO IN
// SYNC: same uniforms, same maths, same constants. The only difference is
// the input — a texture plus a render-px -> content-px matrix (what the
// BitmapShader local matrix does on the AGSL side) instead of an AGSL
// `shader`, sampled with DECAL tiling (transparent black outside).
//
// See duo_unfold.agsl for the model and the meaning of each uniform.

precision highp float;

uniform sampler2D content;
uniform mat3 contentMatrix;  // render px -> content px
uniform vec2 contentSize;    // content size in px
uniform vec2 resolution;
uniform float tiltDegrees;
uniform float eyeDistancePx;
uniform float hingePos;
uniform float eyeX;
uniform float axisSwap;
uniform float paneSide;
uniform float blurSpread;
uniform float darkening;

out vec4 fragColor;

const float GOLDEN_ANGLE = 2.39996322972865332; // radians, Vogel disk spacing
const float TWO_PI = 6.28318530717958648;

// AGSL `content.eval(p)` with DECAL tiling.
vec4 tap(vec2 p) {
    vec2 c = (contentMatrix * vec3(p, 1.0)).xy;
    vec2 inside = step(vec2(0.0), c) * step(c, contentSize);
    return texture(content, c / contentSize) * (inside.x * inside.y);
}

void main() {
    // AGSL's fragCoord: origin top-left, pixel centres at .5.
    vec2 fragCoord = vec2(gl_FragCoord.x, resolution.y - gl_FragCoord.y);

    if (resolution.x <= 1.0 || resolution.y <= 1.0) {
        fragColor = tap(fragCoord);
        return;
    }
    float tilt = radians(clamp(tiltDegrees, 0.0, 60.0));
    if (tilt < 1e-4) {
        fragColor = tap(fragCoord);
        return;
    }

    // Work in a frame where the hinge is always a vertical line at x = hingePos.
    vec2 p = mix(fragCoord, fragCoord.yx, axisSwap);
    vec2 size = mix(resolution, resolution.yx, axisSwap);

    float side = mix(-1.0, 1.0, step(hingePos, p.x)); // -1 left pane, +1 right pane
    if (paneSide * side < -0.5) {
        fragColor = tap(fragCoord); // the half held still stays flat
        return;
    }
    float d = abs(p.x - hingePos);                      // distance from hinge along the glass

    // Glass rotated `tilt` around the hinge line, outer edge rising toward the viewer.
    vec2 glass = vec2(hingePos + side * d * cos(tilt), p.y);
    float gap = d * sin(tilt);
    vec2 eye = vec2(eyeX, size.y * 0.5);

    // Ray eye -> glass pixel, continued to the UI plane z = 0.
    float depth = eyeDistancePx - gap;
    if (depth <= 1e-3) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 hitS = eye + (glass - eye) * (eyeDistancePx / depth);

    float radius = blurSpread * gap;

    // Whole blur kernel misses the UI: black.
    if (hitS.x < -radius || hitS.y < -radius ||
        hitS.x > size.x + radius || hitS.y > size.y + radius) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 hit = mix(hitS, hitS.yx, axisSwap);

    // Frosted glass absorbs: dim in proportion to how much it scatters.
    float atten = max(1.0 - darkening * radius, 0.0);

    if (radius < 0.5) {
        fragColor = vec4(tap(hit).rgb * atten, 1.0);
        return;
    }

    // Vogel disk with per-pixel rotation (banding becomes frosted-glass grain).
    // Fixed 32-iteration loop with a weight mask (no break), as in the AGSL.
    float tapsF = clamp(radius * 2.0, 6.0, 32.0);
    float rotation = fract(sin(dot(fragCoord, vec2(12.9898, 78.233))) * 43758.5453) * TWO_PI;
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 32; i++) {
        float fi = float(i);
        float w = 1.0 - step(tapsF, fi);
        float r = radius * sqrt((fi + 0.5) / tapsF);
        float a = fi * GOLDEN_ANGLE + rotation;
        sum += tap(hit + r * vec2(cos(a), sin(a))).rgb * w;
        wsum += w;
    }
    vec3 avg = sum / max(wsum, 1.0);
    fragColor = vec4(avg * atten, 1.0);
}
