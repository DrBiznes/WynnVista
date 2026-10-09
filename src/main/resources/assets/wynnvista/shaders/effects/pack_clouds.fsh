#version 330 core

// Turns a shader pack's own cloud buffers (PackClouds) into the cloud layer world effects are drawn behind:
// the distance to the pack's cloud on each pixel, and how much it hides.

uniform sampler2D uPackFirst;
uniform sampler2D uPackSecond;
uniform int uKind;            // 0: Photon, 1: BSL, 2: Complementary
uniform vec2 uPackScale;      // x: share of the pack's buffers that covers the view, y: blocks per unit of stored distance
uniform vec2 uViewSize;

out vec4 fragColor;           // r: distance to the cloud in blocks, a: its opacity, 0 where there is none

// BSL and Complementary only record where a cloud is, not how thick. A recorded cloud hides this much.
const float SOLID = 0.85;
const float NONE = 0.999;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize * uPackScale.x;
    float opacity = 0.0;
    float reach = 0.0;
    if (uKind == 0) {
        opacity = 1.0 - clamp(textureLod(uPackFirst, uv, 0.0).a, 0.0, 1.0);
        // The pack marches its clouds at a fraction of the view's size, so their distances come in blocks of
        // pixels. Averaged over the blocks around, each by how much cloud it holds, the line between a
        // cloud in front of an effect and one behind it is smooth instead of stepped.
        ivec2 size = textureSize(uPackSecond, 0);
        ivec2 centre = ivec2(uv * vec2(size));
        float weight = 0.0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                ivec2 texel = clamp(centre + ivec2(x, y) * 3, ivec2(0), size - 1);
                float stored = texelFetch(uPackSecond, texel, 0).x;
                // A buffer the pack never drew clouds into holds zeroes, and a million blocks is "no cloud".
                if (stored <= 0.0 || stored > 1.0e5) continue;
                float share = 1.01 - clamp(texelFetch(uPackFirst, texel, 0).a, 0.0, 1.0);
                if (x == 0 && y == 0) share *= 2.0;
                reach += share * stored;
                weight += share;
            }
        }
        if (weight > 0.0) reach /= weight;
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
