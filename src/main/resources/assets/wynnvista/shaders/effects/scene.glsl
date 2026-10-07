// Shared by every world effect: the view ray of a pixel and the distance to the nearest terrain along it,
// from the vanilla depth buffer and, when present, the LOD renderer's own depth buffer.

uniform sampler2D uSceneDepth;
uniform sampler2D uLodDepth;
uniform sampler3D uNoise;

uniform mat4 uSceneInverse;   // vanilla NDC -> camera-relative world
uniform mat4 uLodInverse;     // LOD NDC -> camera-relative world
uniform vec3 uLodParams;      // x: 1 when a LOD depth is bound, y: its clear value, z: 1 when depth is NDC z directly
uniform vec2 uViewSize;      // size of the target being drawn, which may be smaller than the depth textures
uniform vec3 uBoxMin;         // the effect's bounding box, camera-relative
uniform vec3 uBoxMax;
uniform vec3 uFogColor;
uniform int uSteps;           // ray-march samples, already reduced for small or distant effects
uniform int uOctaves;         // noise octaves worth sampling at this distance

const float INF = 1.0e9;

vec3 unproject(mat4 toWorld, vec3 ndc) {
    vec4 p = toWorld * vec4(ndc, 1.0);
    return p.xyz / p.w;
}

/** Unit view ray of this pixel in camera-relative world space; the camera is the origin. */
vec3 viewRay(vec2 ndc) {
    return normalize(unproject(uSceneInverse, vec3(ndc, 1.0)) - unproject(uSceneInverse, vec3(ndc, -1.0)));
}

/** Where the ray is inside the effect's bounding box; empty (x >= y) when it misses. */
vec2 boxSpan(vec3 dir) {
    vec3 inv = 1.0 / dir;
    vec3 a = uBoxMin * inv;
    vec3 b = uBoxMax * inv;
    vec3 lo = min(a, b);
    vec3 hi = max(a, b);
    return vec2(max(max(lo.x, lo.y), max(lo.z, 0.0)), min(min(hi.x, hi.y), hi.z));
}

float sceneDistance(vec2 uv, vec2 ndc) {
    float nearest = INF;
    float depth = texelFetch(uSceneDepth, ivec2(uv * vec2(textureSize(uSceneDepth, 0))), 0).r;
    // Sky, and LOD depth that Voxy clamps onto the vanilla far plane, are not real vanilla hits.
    if (depth < 0.9999998) {
        nearest = length(unproject(uSceneInverse, vec3(ndc, depth * 2.0 - 1.0)));
    }
    if (uLodParams.x > 0.5) {
        float lod = texelFetch(uLodDepth, ivec2(uv * vec2(textureSize(uLodDepth, 0))), 0).r;
        if (lod != uLodParams.y) {
            float z = uLodParams.z > 0.5 ? lod : lod * 2.0 - 1.0;
            nearest = min(nearest, length(unproject(uLodInverse, vec3(ndc, z))));
        }
    }
    return nearest;
}

const float NOISE_SIZE = 32.0;

float noise(vec3 p) {
    p *= NOISE_SIZE;
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return texture(uNoise, (cell + f + 0.5) / NOISE_SIZE).r;
}

float fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float weight = 0.5;
    float total = 0.0;
    for (int i = 0; i < octaves; i++) {
        sum += weight * noise(p);
        total += weight;
        weight *= 0.5;
        p = p * 2.0 + vec3(0.37, 0.11, 0.73);
    }
    return sum / total;
}

/** Per-pixel offset of the first sample; white noise hides step banding without a visible pattern. */
float dither(vec2 pixel) {
    vec3 p = fract(vec3(pixel.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}
