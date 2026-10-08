// Shared by every world effect: the view ray of a pixel and the distance to the nearest terrain along it,
// from the vanilla depth buffer and, when present, the LOD renderer's own depth buffer.

uniform sampler2D uSceneDepth;
uniform sampler2D uLodDepth;
uniform sampler3D uNoise;
uniform sampler2D uFogProbe;  // 2x1, written by fog_probe.fsh: the fog measurement, then the horizon sky's colour with the effect's own brightness when it was seen
uniform sampler2D uTerrainDepth;  // vanilla depth as it was before a cloud mod drew its clouds into it
uniform sampler2D uCloudDepth;    // depth of that cloud layer alone, 1 where it wrote none
uniform sampler2D uCloudColor;    // the cloud layer alone: premultiplied colour, opacity in alpha

uniform mat4 uSceneInverse;   // vanilla NDC -> camera-relative world
uniform mat4 uLodInverse;     // LOD NDC -> camera-relative world
uniform vec3 uLodParams;      // x: 1 when a LOD depth is bound, y: its clear value, z: 1 when depth is NDC z directly
uniform vec3 uCloudParams;    // x: 1 when a cloud mod's layer is bound, 2 when a shader pack's clouds are (uCloudColor then holds r: distance in blocks, a: opacity), y: 1 when the image holds the layer's colour unchanged, z: its height above the camera
uniform vec2 uViewSize;     // size of the target being drawn, which may be smaller than the depth textures
uniform vec3 uBoxMin;         // the effect's bounding box, camera-relative
uniform vec3 uBoxMax;
uniform vec3 uFogColor;
uniform sampler2D uFogTable;  // a pack's or LOD mod's fog (FogTable): x over ground distance, y over height; r: haze, g: fade
uniform vec4 uFogTableRange;  // x: 1 / ground distance covered, 0 without a table, y: lowest height, camera-relative, z: 1 / height covered, w: 1 when the probe's measurement still applies
uniform vec4 uFogTableColor;  // rgb: the fog's colour, a: 1 when it is known, 0 to take it from the probe
uniform mat4 uSceneForward;   // camera-relative world -> vanilla clip space
uniform float uSkyMatch;      // 1 while a shader pack renders and the effect is lit to match its sky
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
    if (uCloudParams.x > 0.5 && uCloudParams.x < 1.5) {
        // Where the vanilla depth is a cloud's, the terrain behind it is in the copy made before the clouds.
        ivec2 texel = ivec2(uv * vec2(textureSize(uCloudDepth, 0)));
        if (depth >= texelFetch(uCloudDepth, texel, 0).r - 1.0e-6) depth = texelFetch(uTerrainDepth, texel, 0).r;
    }
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

/**
 * Puts an effect's premultiplied colour behind the fog already in the world image: as terrain at the
 * effect's distance loses its detail to fog, the effect takes on that terrain's colour. While a fog model
 * replaces the measurement the probe's visibility is 1 and this changes nothing.
 */
vec3 throughFog(vec3 colour, float alpha) {
    vec4 fog = texelFetch(uFogProbe, ivec2(0), 0);
    return mix(fog.rgb * alpha, colour, fog.a);
}

/**
 * The modelled fog between the camera and a camera-relative position: x is the share of an effect there that
 * takes the fog's colour, y the share that fades into what is behind it. Zero without a table.
 */
vec2 tableFog(vec3 position) {
    vec2 uv = vec2(length(position.xz) * uFogTableRange.x, (position.y - uFogTableRange.y) * uFogTableRange.z);
    vec2 edge = 0.5 / vec2(textureSize(uFogTable, 0));
    return textureLod(uFogTable, clamp(uv, edge, 1.0 - edge), 0.0).rg;
}

/**
 * Distance along this pixel's ray to the cloud layer, INF where it has no cloud, and the cloud itself:
 * premultiplied colour, opacity in alpha.
 */
float cloudDistance(vec2 uv, vec2 ndc, vec3 dir, out vec4 cloud) {
    cloud = vec4(0.0);
    if (uCloudParams.x < 0.5) return INF;
    ivec2 texel = ivec2(uv * vec2(textureSize(uCloudColor, 0)));
    cloud = texelFetch(uCloudColor, texel, 0);
    if (uCloudParams.x > 1.5) {
        // A shader pack's clouds: only where they are and how much they hide is known, not their colour.
        float reach = cloud.r;
        cloud = vec4(0.0, 0.0, 0.0, cloud.a);
        return cloud.a <= 0.0 ? INF : reach;
    }
    if (cloud.a <= 0.0) return INF;
    float depth = texelFetch(uCloudDepth, texel, 0).r;
    if (depth < 0.9999998) return length(unproject(uSceneInverse, vec3(ndc, depth * 2.0 - 1.0)));
    // Clouds past the far plane have no depth of their own: they are where the ray meets the layer's height.
    float t = uCloudParams.z / dir.y;
    return t > 0.0 ? t : length(unproject(uSceneInverse, vec3(ndc, 1.0)));
}

/**
 * The finished pixel of an effect: `whole` is the march's premultiplied colour and opacity, `front` the same
 * for the part of it nearer than the clouds. Both get the haze and fog, then the cloud goes between them, so
 * the part behind is seen through the cloud. `position` is where the effect begins on this pixel's ray, and
 * `haze` the effect's own guess at the haze there, used when no pack's or LOD mod's fog is modelled.
 */
vec4 underClouds(vec4 whole, vec4 front, float haze, vec4 cloud, vec3 position) {
    vec3 hazeColour = uFogColor;
    if (uFogTableRange.x > 0.0) {
        vec2 fog = tableFog(position);
        // Towards the LOD border the effect thins out into the sky behind it, as the terrain does.
        whole *= 1.0 - fog.y;
        front *= 1.0 - fog.y;
        if (uFogTableRange.w < 0.5) {
            haze = fog.x;
            // The sky's colour at the horizon, or nothing yet.
            vec3 seen = texelFetch(uFogProbe, ivec2(1, 0), 0).rgb;
            hazeColour = uFogTableColor.a > 0.5 ? uFogTableColor.rgb : (seen.r < 0.0 ? uFogColor : seen);
        }
    }
    whole.rgb = throughFog(mix(whole.rgb, hazeColour * whole.a, haze), whole.a);
    front.rgb = throughFog(mix(front.rgb, hazeColour * front.a, haze), front.a);
    vec3 colour = front.rgb + (whole.rgb - front.rgb) * (1.0 - cloud.a);
    float behind = whole.a - front.a;
    // Blending the effect over the image also covers the cloud that is in front of it. With the cloud's
    // colour known that share is put back exactly; otherwise the effect covers less where the cloud is.
    if (uCloudParams.y > 0.5) return vec4(colour + behind * cloud.rgb, whole.a);
    return vec4(colour, front.a + behind * (1.0 - cloud.a));
}

const float GAIN_MIN = 0.35;   // an effect is never dimmed or brightened further than this to match a pack
const float GAIN_MAX = 2.5;
const float SKY_HUE = 0.8;     // share of the pack's sky hue taken on by the effect's sky light
const float SUN_HUE = 0.5;     // and by its sun or moon light, whose colour the pack also grades

/** Set by skyLight(): what the effect's lit colour is multiplied by. Its own glow is left alone. */
float skyGain = 1.0;
/** Set by skyLight(): what the effect's sun or moon light is multiplied by. */
vec3 sunTint = vec3(1.0);

float luminance(vec3 colour) {
    return dot(colour, vec3(0.2126, 0.7152, 0.0722));
}

/**
 * The sky light for an effect whose own model gives `ambient`. Without a shader pack that is tinted by the
 * game's fog colour. Under one, the pack has drawn a sky the game knows nothing about: the light takes the
 * hue of that sky at the horizon, and skyGain brings the effect's brightness to it, so the effect is as
 * dim, bright, blue or pink as the scene the pack exposed and graded. The sky is compared with the effect's
 * own brightness at the time it was seen, so while it is out of view the effect still follows the time of day.
 */
vec3 skyLight(vec3 ambient) {
    skyGain = 1.0;
    sunTint = vec3(1.0);
    if (uSkyMatch < 0.5) return mix(ambient, uFogColor, 0.45);
    vec4 sky = texelFetch(uFogProbe, ivec2(1, 0), 0);
    if (sky.r < 0.0) return ambient;
    float seen = luminance(sky.rgb);
    vec3 hue = sky.rgb / max(seen, 1.0e-3);
    skyGain = clamp(seen / max(sky.a, 1.0e-3), GAIN_MIN, GAIN_MAX);
    sunTint = mix(vec3(1.0), hue, SUN_HUE);
    // The sky light keeps its strength and trades its own hue for the pack's.
    return mix(ambient, luminance(ambient) * hue, SKY_HUE);
}

const float NOISE_SIZE = 32.0;

float noise(vec3 p) {
    p *= NOISE_SIZE;
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return textureLod(uNoise, (cell + f + 0.5) / NOISE_SIZE, 0.0).r;
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
