// Shared by every world effect: the view ray of a pixel and the distance to the nearest terrain along it,
// from the vanilla depth buffer and, when present, the LOD renderer's own depth buffer.

uniform sampler2D uSceneDepth;
uniform sampler2D uLodDepth;
uniform sampler3D uNoise;
uniform sampler2D uFogProbe;  // written by fog_probe.fsh: the fog measurement, then the sky's colour in SKY_BANDS bands of elevation; second row: its bright parts
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
uniform float uSkyMatch;      // 1 while a shader pack renders and the effect is lit by the sky that pack drew
uniform sampler2D uOpaqueDepth;   // vanilla depth as it was before translucents were drawn (a shader pack's depthtex1)
uniform sampler2D uLodSurface;    // the LOD renderer's depth with its water
uniform sampler2D uLodOpaque;     // and without it; the same texture as uLodSurface when the two are not kept apart
uniform vec2 uMirror;         // x: 1 while an effect's reflection in water is drawn instead of the effect, y: seconds, for the ripples
uniform vec3 uMirrorCamera;   // the camera's place in the ripple pattern, which repeats
uniform vec3 uWater;          // how much the pack's water reflects: x: seen from straight above, y: exponent of the rise towards a grazing view, z: strength
uniform int uSteps;           // ray-march samples, already reduced for small or distant effects
uniform int uOctaves;         // noise octaves worth sampling at this distance

const float INF = 1.0e9;
const int SKY_BANDS = 8;      // EffectFog.SKY_BANDS: equal steps of the sine of the elevation, horizon first

/**
 * Where this pixel's ray starts, camera-relative: the camera, or for a reflection the point behind the water
 * that the reflected ray seems to come from, so that distances along the ray are the whole path to the eye.
 */
vec3 rayOrigin = vec3(0.0);
/** Distance along the ray before which nothing is drawn: the way to the water, for a reflection. */
float rayStart = 0.0;
/** Share of what the ray gathers that reaches the eye: the water's reflectance, for a reflection. */
float rayWeight = 1.0;

vec3 unproject(mat4 toWorld, vec3 ndc) {
    vec4 p = toWorld * vec4(ndc, 1.0);
    return p.xyz / p.w;
}

/** Unit view ray of this pixel in camera-relative world space; the camera is the origin. */
vec3 viewRay(vec2 ndc) {
    return normalize(unproject(uSceneInverse, vec3(ndc, 1.0)) - unproject(uSceneInverse, vec3(ndc, -1.0)));
}

/** Where the ray from rayOrigin is inside the effect's bounding box; empty (x >= y) when it misses. */
vec2 boxSpan(vec3 dir) {
    vec3 inv = 1.0 / dir;
    vec3 a = (uBoxMin - rayOrigin) * inv;
    vec3 b = (uBoxMax - rayOrigin) * inv;
    vec3 lo = min(a, b);
    vec3 hi = max(a, b);
    return vec2(max(max(lo.x, lo.y), max(lo.z, rayStart)), min(min(hi.x, hi.y), hi.z));
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

int skyBands[SKY_BANDS];
bool skyRead = false;

/**
 * The sky as the probe saw it at an elevation given by its sine: its average colour, or with `bright` the
 * colour of its bright parts. Below the horizon it is the horizon's. A band with no sky in view has the
 * colour of the nearest band that has. Negative while no sky has been seen at all.
 */
vec3 skyAt(float up, bool bright) {
    if (!skyRead) {
        skyRead = true;
        bool seen[SKY_BANDS];
        for (int i = 0; i < SKY_BANDS; i++) seen[i] = texelFetch(uFogProbe, ivec2(1 + i, 0), 0).r >= 0.0;
        for (int i = 0; i < SKY_BANDS; i++) {
            skyBands[i] = i;
            for (int k = 0; k < SKY_BANDS; k++) {
                if (i - k >= 0 && seen[i - k]) { skyBands[i] = i - k; break; }
                if (i + k < SKY_BANDS && seen[i + k]) { skyBands[i] = i + k; break; }
            }
        }
    }
    float band = clamp(up * float(SKY_BANDS) - 0.5, 0.0, float(SKY_BANDS - 1));
    int below = min(int(band), SKY_BANDS - 2);
    int row = bright ? 1 : 0;
    return mix(texelFetch(uFogProbe, ivec2(1 + skyBands[below], row), 0).rgb,
            texelFetch(uFogProbe, ivec2(1 + skyBands[below + 1], row), 0).rgb, band - float(below));
}

/**
 * Share of an effect at distance `t` along the ray that is in front of the cloud at `cloudAt`. A cloud mod's
 * layer has a surface. A shader pack's distance is somewhere inside a cloud it marched through, so there the
 * effect passes behind it gradually.
 */
float beforeCloud(float t, float cloudAt) {
    return uCloudParams.x > 1.5 ? 1.0 - smoothstep(0.8, 1.25, t / cloudAt) : step(t, cloudAt);
}

vec4 gatheredBefore = vec4(0.0);
float shareBefore = 1.0;

/**
 * Keeps `front`, the part of a march that is in front of the clouds, up to date. Called with what the march
 * has gathered so far each time it is about to add something at distance `t`, and once more when it is done.
 */
void gather(inout vec4 front, vec3 colour, float transmittance, float t, float cloudAt) {
    vec4 gathered = vec4(colour, 1.0 - transmittance);
    front += (gathered - gatheredBefore) * shareBefore;
    gatheredBefore = gathered;
    shareBefore = beforeCloud(t, cloudAt);
}

/**
 * Distance along this pixel's ray to the cloud layer, INF where it has no cloud, and the cloud itself:
 * premultiplied colour, opacity in alpha.
 */
float cloudDistance(vec2 uv, vec2 ndc, vec3 dir, out vec4 cloud) {
    cloud = vec4(0.0);
    // A reflection is drawn without the clouds mirrored in front of it.
    if (uCloudParams.x < 0.5 || uMirror.x > 0.5) return INF;
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
 * for the part of it nearer than the clouds, each of the march's samples weighted by beforeCloud(). Both get the haze and fog, then the cloud goes between them, so
 * the part behind is seen through the cloud. `position` is where the effect begins on this pixel's ray, and
 * `haze` the effect's own guess at the haze there, used when no pack's or LOD mod's fog is modelled. Of a
 * reflection, only what the water reflects is returned.
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
            // Fog in front of an effect has the colour of the sky in its direction: at the horizon what
            // distant terrain fades into, higher up what the pack drew there. Nothing yet if none was seen.
            vec3 seen = skyAt(position.y / max(length(position), 1.0e-3), false);
            hazeColour = uFogTableColor.a > 0.5 ? uFogTableColor.rgb : (seen.r < 0.0 ? uFogColor : seen);
        }
    }
    whole.rgb = throughFog(mix(whole.rgb, hazeColour * whole.a, haze), whole.a);
    front.rgb = throughFog(mix(front.rgb, hazeColour * front.a, haze), front.a);
    vec3 colour = front.rgb + (whole.rgb - front.rgb) * (1.0 - cloud.a);
    float behind = whole.a - front.a;
    // Blending the effect over the image also covers the cloud that is in front of it. With the cloud's
    // colour known that share is put back exactly; otherwise the effect covers less where the cloud is.
    vec4 seen = uCloudParams.y > 0.5 ? vec4(colour + behind * cloud.rgb, whole.a)
            : vec4(colour, front.a + behind * (1.0 - cloud.a));
    return seen * rayWeight;
}

const float SKY_SHARE = 1.2;   // of the brightness of a pack's sky that an effect's sky light has
const float SUN_SHARE = 0.75;  // and of the brightness of that sky's bright parts that its sun or moon light has
const float SKY_HUE = 0.8;     // share of the pack's sky hue taken on by the effect's sky light
// The sun, the moon and the stars are in the sky too: its bright parts count for no more than this many
// times its average.
const float BRIGHTEST = 2.0;

/** Set by skyLight(): the sun or moon light on the effect. */
vec3 sunLight = vec3(0.0);

float luminance(vec3 colour) {
    return dot(colour, vec3(0.2126, 0.7152, 0.0722));
}

/**
 * The light on a part of an effect seen at an elevation whose sine is `up`: returns its sky light and sets
 * sunLight, given the `ambient` and `sun` of the effect's own model. Without a shader pack those are used,
 * the sky light tinted by the game's fog colour. Under one, the pack has drawn, exposed and graded a sky the
 * game knows nothing about, and the effect is lit by that sky as it is behind it: the sky light has its
 * colour and brightness there, and the sun or moon light the brightness of its bright parts. Where clouds
 * stand out from the sky their lit sides also show the colour of the pack's sun or moon; an even sky does
 * not, and there the model's own sun keeps its colour.
 */
vec3 skyLight(vec3 ambient, vec3 sun, float up) {
    sunLight = sun;
    if (uSkyMatch < 0.5) return mix(ambient, uFogColor, 0.45);
    vec3 sky = skyAt(up, false);
    if (sky.r < 0.0) return ambient;
    vec3 bright = max(skyAt(up, true), 0.0);
    float seen = max(luminance(sky), 1.0e-3);
    float most = clamp(luminance(bright), seen, BRIGHTEST * seen);
    float clouds = smoothstep(1.1, 1.4, most / seen);
    vec3 hue = mix(sun / max(luminance(sun), 1.0e-3), bright / max(luminance(bright), 1.0e-3), clouds);
    sunLight = SUN_SHARE * most * hue;
    return SKY_SHARE * seen * mix(ambient / max(luminance(ambient), 1.0e-3), sky / seen, SKY_HUE);
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

const float RIPPLE = 0.03;          // how far ripples tilt the water's surface
const float RIPPLE_SIZE = 6.0;      // blocks from one ripple to the next
const int TRACE_STEPS = 16;         // samples of the scene along a reflected ray

/**
 * The nearest surface on this pixel, camera-relative, and whether it is translucent with something else
 * behind it, as water is: its depth differs from the depth drawn without translucents.
 */
bool translucentSurface(vec2 uv, vec2 ndc, out vec3 point) {
    point = vec3(0.0);
    float nearest = INF;
    bool translucent = false;
    if (uLodParams.x > 0.5) {
        ivec2 texel = ivec2(uv * vec2(textureSize(uLodSurface, 0)));
        float surface = texelFetch(uLodSurface, texel, 0).r;
        if (surface != uLodParams.y) {
            point = unproject(uLodInverse, vec3(ndc, uLodParams.z > 0.5 ? surface : surface * 2.0 - 1.0));
            nearest = length(point);
            translucent = texelFetch(uLodOpaque, texel, 0).r != surface;
        }
    }
    float depth = texelFetch(uSceneDepth, ivec2(uv * vec2(textureSize(uSceneDepth, 0))), 0).r;
    if (depth < 0.9999998) {
        vec3 hit = unproject(uSceneInverse, vec3(ndc, depth * 2.0 - 1.0));
        // Voxy also writes its LODs into the vanilla depth, after the copy without translucents was taken.
        // Only a hit clearly nearer than the LOD is the vanilla world's.
        if (length(hit) < nearest * 0.98 - 0.5) {
            point = hit;
            translucent = texelFetch(uOpaqueDepth, ivec2(uv * vec2(textureSize(uOpaqueDepth, 0))), 0).r > depth;
        }
    }
    return translucent;
}

/**
 * Turns this pixel's ray into its reflection in the water on this pixel: sets rayOrigin, rayStart and
 * rayWeight and replaces dir. Returns how far along the new ray it is clear of terrain, 0 where the pixel is
 * not level water seen from above or the reflected ray misses the effect.
 */
float mirror(vec2 uv, vec2 ndc, inout vec3 dir) {
    vec3 point;
    bool water = translucentSurface(uv, ndc, point);
    // Taken before any branch on the pixel: derivatives are undefined after one.
    vec3 across = dFdx(point);
    vec3 along = dFdy(point);
    if (!water || dir.y > -0.003) return 0.0;
    // Glass walls, portals and the like are translucent too; only a level surface is taken for water.
    vec3 facing = cross(across, along);
    if (abs(facing.y) < 0.9 * length(facing)) return 0.0;

    // Ripples tilt the surface a little. They are dropped where a pixel spans more water than one covers.
    float reach = length(point);
    vec3 at = vec3((point.xz + uMirrorCamera.xz) / RIPPLE_SIZE, uMirror.y * 0.5) / NOISE_SIZE;
    vec2 tilt = vec2(noise(at), noise(at + vec3(0.37, 0.61, 0.5))) - 0.5;
    tilt *= RIPPLE * (1.0 - smoothstep(0.2, 0.8, max(length(across), length(along)) / RIPPLE_SIZE));
    vec3 normal = normalize(vec3(tilt.x, 1.0, tilt.y));
    vec3 reflected = reflect(dir, normal);
    if (reflected.y <= 0.0) reflected = vec3(dir.x, -dir.y, dir.z);

    rayOrigin = point - reflected * reach;
    rayStart = reach;
    rayWeight = uWater.z * (uWater.x + (1.0 - uWater.x) * pow(1.0 - clamp(dot(-dir, normal), 0.0, 1.0), uWater.y));
    dir = reflected;
    vec2 span = boxSpan(dir);
    if (span.y <= span.x) return 0.0;

    // Terrain between the water and the effect hides the reflection. The reflected ray is followed across
    // the image, in steps that grow with distance, until it passes just behind something the image holds.
    // A sample outside the image tells nothing and is taken as clear.
    float extent = span.y - reach;
    float jitter = dither(gl_FragCoord.xy + 11.0);
    float previous = 0.0;
    for (int i = 0; i < TRACE_STEPS; i++) {
        float f = (float(i) + jitter) / float(TRACE_STEPS);
        float s = extent * f * f;
        vec3 q = point + dir * s;
        vec4 clip = uSceneForward * vec4(q, 1.0);
        vec2 spot = clip.xy / max(clip.w, 1.0e-4);
        if (clip.w > 0.05 && abs(spot.x) < 1.0 && abs(spot.y) < 1.0) {
            float seen = sceneDistance(spot * 0.5 + 0.5, spot);
            float behind = length(q) - seen;
            // Something far in front of the ray's point, as seen from the camera, is not in the ray's way.
            if (behind > 1.0 + 0.003 * seen && behind < (s - previous) * 1.5 + 4.0) return reach + previous;
        }
        previous = s;
    }
    return span.y;
}

/**
 * Starts this pixel's ray: its direction, and how far along it the scene lets an effect be drawn. That is
 * the view ray up to the nearest terrain or, while reflections are drawn, its reflection in water.
 */
float beginRay(vec2 uv, vec2 ndc, out vec3 dir) {
    dir = viewRay(ndc);
    if (uMirror.x < 0.5) return sceneDistance(uv, ndc);
    return mirror(uv, ndc, dir);
}
