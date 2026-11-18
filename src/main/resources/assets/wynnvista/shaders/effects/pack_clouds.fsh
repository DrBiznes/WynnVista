#version 330 core

// Turns a shader pack's own cloud buffers (PackClouds) into the cloud layer world effects are drawn behind:
// the distance to the pack's cloud on each pixel, and how much it hides.

uniform sampler2D uPackFirst;
uniform sampler2D uPackSecond;
uniform sampler2D uPackSpread; // last of two stages: what stage 1 wrote
uniform int uKind;            // 0: Photon, 1: BSL, 2: Complementary
uniform int uStage;           // 1: the first of two smoothing stages, which writes r: summed distance, g: summed weight, b: summed cover; 0: the second; 2: one cheaper stage in their place
uniform vec2 uPackScale;      // x: share of the pack's buffers that covers the view, y: blocks per unit of stored distance
uniform vec2 uViewSize;

out vec4 fragColor;           // r: distance to the cloud in blocks, a: its opacity, 0 where there is none

// BSL and Complementary only record where a cloud is, not how thick. A recorded cloud hides this much.
const float SOLID = 0.85;
const float NONE = 0.999;

// Photon stores a million blocks where there is no cloud, and its own filtering over space and time mixes that
// million into the distances along every cloud's edge, by a different amount each frame: there a stored
// distance is thousands of blocks too far, never too near, and flickers. A pixel's distance is therefore a mean
// over the texels around it in which a texel counts by how much cloud it holds and by the inverse square of
// its distance, so that the near, true ones decide it, under a kernel that falls smoothly to nothing REACH
// steps away, so that it changes smoothly from pixel to pixel. A step is a pixel of a view 720 high. The kernel
// is a product of one along x and one along y: uStage 1 sums along x, and the last stage sums that along y.
// With that smoothing switched off (uStage 2) the mean is over nine texels three apart with the same weights:
// cheaper, and an edge still flickers where all nine are too far.
// BSL's and Complementary's distances are sound, and one stage over nine texels is their usual reading. The two
// stages can be asked for with them as well (PackClouds.Smoothing.ALWAYS): the same mean of the distances, and
// the cloud's share of the texels under the same kernel as its opacity, which gives a wider, softer edge.
const int REACH = 6;         // PackClouds.SPREAD_TAPS
const float NEAR = 100.0;   // only keeps the weights in a float's range

float kernel(int i) {
    float x = float(i) / float(REACH + 1);
    float k = 1.0 - x * x;
    return k * k * k * k;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize * uPackScale.x;
    float opacity = 0.0;
    float reach = 0.0;
    if (uStage == 1) {
        ivec2 size = uKind == 0 ? textureSize(uPackSecond, 0) : textureSize(uPackFirst, 0);
        ivec2 centre = ivec2(uv * vec2(size));
        float spread = max(1.0, uViewSize.y / 720.0);
        vec3 sum = vec3(0.0);
        for (int i = -REACH; i <= REACH; i++) {
            ivec2 texel = clamp(centre + ivec2(int(round(float(i) * spread)), 0), ivec2(0), size - 1);
            float stored;
            float share = kernel(i);
            if (uKind == 0) {
                stored = texelFetch(uPackSecond, texel, 0).x;
                // A buffer the pack never drew clouds into holds zeroes, and a million blocks is "no cloud".
                if (stored <= 0.0 || stored > 1.0e5) continue;
                share *= 1.01 - clamp(texelFetch(uPackFirst, texel, 0).a, 0.0, 1.0);
            } else {
                // Complementary keeps an unrelated value in the last pixel of this buffer.
                if (uKind == 2 && texel == size - 1) continue;
                vec4 cell = texelFetch(uPackFirst, texel, 0);
                float value = uKind == 1 ? cell.r : cell.a;
                if (value >= NONE) continue;
                stored = (uKind == 1 ? value : value * value) * uPackScale.y;
            }
            float near = NEAR / max(stored, 1.0);
            sum += vec3(share * near * near * vec2(stored, 1.0), kernel(i));
        }
        fragColor = vec4(sum, 0.0);
        return;
    }
    if (uKind == 0 && uStage == 2) {
        opacity = 1.0 - clamp(textureLod(uPackFirst, uv, 0.0).a, 0.0, 1.0);
        ivec2 size = textureSize(uPackSecond, 0);
        ivec2 centre = ivec2(uv * vec2(size));
        vec2 sum = vec2(0.0);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                ivec2 texel = clamp(centre + ivec2(x, y) * 3, ivec2(0), size - 1);
                float stored = texelFetch(uPackSecond, texel, 0).x;
                if (stored <= 0.0 || stored > 1.0e5) continue;
                float near = NEAR / max(stored, 1.0);
                float share = (1.01 - clamp(texelFetch(uPackFirst, texel, 0).a, 0.0, 1.0)) * near * near;
                sum += share * vec2(stored, 1.0);
            }
        }
        if (sum.y > 0.0) reach = sum.x / sum.y;
        else opacity = 0.0;
    } else if (uStage == 0) {
        ivec2 size = textureSize(uPackSpread, 0);
        ivec2 centre = ivec2(gl_FragCoord.xy);
        float spread = max(1.0, uViewSize.y / 720.0);
        vec3 sum = vec3(0.0);
        float whole = 0.0;
        for (int i = -REACH; i <= REACH; i++) {
            ivec2 texel = clamp(centre + ivec2(0, int(round(float(i) * spread))), ivec2(0), size - 1);
            sum += texelFetch(uPackSpread, texel, 0).xyz * kernel(i);
            whole += kernel(i);
        }
        // Photon records how much of the sky shows through its cloud; the others only where one is.
        opacity = uKind == 0 ? 1.0 - clamp(textureLod(uPackFirst, uv, 0.0).a, 0.0, 1.0)
                : sum.z / (whole * whole) * SOLID;
        if (sum.y > 0.0) reach = sum.x / sum.y;
        else opacity = 0.0;
    } else {
        // A yes-or-no cloud per pixel: its share of a small neighbourhood softens the edge.
        ivec2 size = textureSize(uPackFirst, 0);
        ivec2 centre = ivec2(uv * vec2(size));
        float covered = 0.0;
        float nearest = 1.0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                ivec2 texel = clamp(centre + ivec2(x, y) * 2, ivec2(0), size - 1);
                // Complementary keeps an unrelated value in the last pixel of this buffer.
                if (uKind == 2 && texel == size - 1) continue;
                vec4 stored = texelFetch(uPackFirst, texel, 0);
                float value = uKind == 1 ? stored.r : stored.a;
                if (value < NONE) {
                    covered += 1.0;
                    nearest = min(nearest, value);
                }
            }
        }
        opacity = covered / 9.0 * SOLID;
        reach = (uKind == 1 ? nearest : nearest * nearest) * uPackScale.y;
    }
    fragColor = vec4(reach, 0.0, 0.0, opacity);
}
