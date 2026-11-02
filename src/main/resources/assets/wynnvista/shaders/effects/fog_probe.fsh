#version 330 core

// Reads two things from the world image into a row of texels, each smoothed over time. The world image is
// sampled by fog_probe_samples.fsh, one sample per pixel; this adds those samples up.
//
// Texel 0, the fog at an effect's distance, measured when nothing is known about it (mode 0: an unrecognised
// shader pack, WynnIris' post-process fog, no LOD fog model). Terrain that fog has swallowed has lost its
// detail and has the fog's colour. Effects apply it with throughFog(). When a FogModel says how much fog
// there is (mode 1) nothing is measured and the texel reads "clear".
//
// Texels 1 to SKY_BANDS, the colour of the sky in the effect's direction, one texel per band of elevation
// from the horizon to straight up, clouds included. The lowest is what distant terrain fades into, so it is
// the colour of a modelled fog. Under a shader pack the effects' own lighting is matched to the band each
// part of an effect is seen in (skyLight() in scene.glsl), so an effect looked up at from nearby has the
// colours of the sky behind it and not those of a horizon that may not even be in view. The second row holds
// the colour of each band's bright parts: where a pack has drawn clouds, those are their lit sides. The
// alpha of each is how bright the effect's own light model was when that sky was seen: a sky that has been out
// of view since another time of day is scaled by how much the model has changed since.

#include "scene.glsl"

#include "fog_probe_layout.glsl"

uniform sampler2D uSamples;   // fog_probe_samples.fsh's samples of this frame
uniform sampler2D uPrevious;  // last frame's result
uniform vec2 uContrast;       // terrain detail at which it counts as swallowed, and as fully visible
uniform float uRate;          // share of this frame's measurement blended in; 1 replaces the old value
uniform int uProbeMode;       // 0: measure the fog, 1: a model knows it

out vec4 fragColor;           // texel 0: rgb colour of terrain at the effect's distance, a: how much detail it keeps
                              // others: rgb colour of the sky in that band, negative while none was seen, a: uReference when it was
                              // second row: unused, then the colour of each band's bright parts

const float ENOUGH = 8.0;     // terrain samples for a full-weight measurement

/**
 * Colour of the sky across the searched columns, in one band of elevation: its average, or with `bright`
 * an average in which each sample counts by the square of its brightness.
 */
void sky(int band, bool bright) {
    vec3 colour = vec3(0.0);
    float count = 0.0;
    for (int x = 0; x < COLUMNS; x++) {
        for (int e = 0; e < ELEVATIONS; e++) {
            vec4 taken = texelFetch(uSamples, ivec2(x, band * ELEVATIONS + e), 0);
            if (taken.r < 0.0) continue;
            vec3 seen = taken.rgb;
            float weight = 1.0;
            if (bright) {
                weight = dot(seen, vec3(0.2126, 0.7152, 0.0722));
                weight = weight * weight + 1.0e-4;
            }
            colour += seen * weight;
            count += weight;
        }
    }
    vec4 previous = texelFetch(uPrevious, ivec2(1 + band, bright ? 1 : 0), 0);
    bool fresh = uRate >= 1.0 || previous.r < 0.0;
    if (count <= 0.0) {
        fragColor = uRate >= 1.0 ? vec4(-1.0, -1.0, -1.0, 1.0) : previous;
        return;
    }
    vec4 seen = vec4(colour / count, uReference);
    fragColor = fresh ? seen : mix(previous, seen, uRate);
}

void main() {
    if (gl_FragCoord.x > 1.0) {
        sky(int(gl_FragCoord.x) - 1, gl_FragCoord.y > 1.0);
        return;
    }
    if (gl_FragCoord.y > 1.0) {
        fragColor = vec4(0.0);
        return;
    }
    if (uProbeMode == 1) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 colour = vec3(0.0);
    float detail = 0.0;
    float count = 0.0;
    for (int x = 0; x < COLUMNS; x++) {
        for (int y = 0; y < ROWS; y++) {
            vec4 taken = texelFetch(uSamples, ivec2(x, SKY_ROWS + y), 0);
            if (taken.r < 0.0) continue;
            detail += taken.a;
            colour += taken.rgb;
            count += 1.0;
        }
    }
    vec4 previous = texelFetch(uPrevious, ivec2(0), 0);
    // A texture that has never been measured into holds no colour.
    previous.rgb = max(previous.rgb, 0.0);
    // Without terrain at the right distance the previous value stands (or, on a fresh start, "clear").
    if (count < 0.5) {
        fragColor = uRate >= 1.0 ? vec4(0.0, 0.0, 0.0, 1.0) : previous;
        return;
    }
    float visible = clamp((sqrt(sqrt(detail / count)) - uContrast.x) / (uContrast.y - uContrast.x), 0.0, 1.0);
    vec4 measured = vec4(colour / count, visible);
    fragColor = mix(previous, measured, uRate >= 1.0 ? 1.0 : uRate * min(count / ENOUGH, 1.0));
}
