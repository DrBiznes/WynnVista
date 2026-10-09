#version 330 core

// The void under the Sky Islands, realistic style: soft clouds where the blocky style has its clumps of cubes,
// in the same white, rose and blue, a thin haze around them, and below them a dark void with a few fallen
// islands and a rare nebula of smooth gas, filaments and points of starlight. The clouds are ray-marched; the
// haze is worked out exactly along the ray.

#include "scene.glsl"

#include "sky_islands_void_shape.glsl"

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const int MAX_STEPS = 56;               // samples along the ray through the cloud; more do not show
const float STEP_ORIGIN = 24.0;         // samples are spaced evenly in log(distance + this), so near cloud gets the most
const float PUFF_FOOT = 2.5;            // height of a cloud at its rim, in blocks
const float PUFF_RISE = 95.0;           // and how much it gains per unit of noise past that
const float PUFF_MAX = 14.0;
const float PUFF_FILL = 0.10;           // rise of the noise over which a cloud goes from its rim to its full body
const float EROSION = 0.6;              // how far the fine noise moves the cloud's surface through its body; higher is more ragged
const float DETAIL = 4.0;               // repeats of the fine noise per PERIOD: its largest billows are 16 blocks
const float EXTINCTION = 0.3;           // per block of full cloud
const float SHADOW_REACH = 5.0;         // blocks towards the light at which a sample looks for cloud in its way
const float SHADOW = 2.2;               // how dark that makes it

const float NEBULA_GAP = 90.0;          // how much deeper a nebula's second cloud lies than its first
const float NEBULA_STARS = 0.06;        // share of a nebula's squares that hold a star
const float STAR_SIZE = 0.45;           // radius of a star, in blocks at the nebula's depth
const float ISLE_SOFT = 0.012;          // rise of the noise over which an island's edge goes from void to land
const float ISLE_WARP = 0.006;          // how far an island's outline is bent, in noise repeats

vec3 gathered = vec3(0.0);      // premultiplied colour of the cloud the ray has met, front to back
float through = 1.0;            // how much still shows through it
float reached = 0.0;            // distance along the ray of that cloud, weighted by how much of it shows
float firstCloud = -1.0;        // where the ray first met cloud, negative until then

/**
 * The body of the cloud at q: x and z in blocks within the noise pattern, y in blocks above the floor of the
 * lowest clumps. x: how much cloud, 0..1, before the fine noise; y: the height within the cloud, 0 at its
 * base and 1 at its top. A cloud stands where the blocky style has a clump and is about as tall as the clump's
 * cubes are stacked: flat underneath, heaped up in lumps of some 8 blocks on top, thin at the rim.
 */
vec2 puff(vec3 q) {
    vec2 p = q.xz / PERIOD;
    float past = clumpCover(p) - COVER + CRUMB_MARGIN;
    if (past <= 0.0) return vec2(0.0);
    float base = CELL * smoothstep(0.42, 0.58, clumpLevel(p));
    float tall = min(PUFF_FOOT + PUFF_RISE * past, PUFF_MAX) * (0.55 + 0.9 * noise(vec3(p * 8.0, 0.56)));
    float v = (q.y - base) / tall;
    if (v <= -0.15 || v >= 1.0) return vec2(0.0);
    return vec2(smoothstep(0.0, PUFF_FILL, past) * smoothstep(-0.15, 0.15, v) * (1.0 - smoothstep(0.45, 1.0, v)), v);
}

/**
 * Density of the cloud at q for body `body`, 0..1. The cloud ends where its body falls below a level the fine
 * noise sets, so its surface swells and folds into billows and its rim breaks up into wisps.
 */
float billow(vec3 q, float body, int octaves) {
    // The fine noise rises slowly through the cloud, so its surface is never still.
    float detail = fbm(q * (DETAIL / PERIOD) - vec3(0.0, uPhase * 4.0, 0.0), octaves);
    return clamp((body - 0.1 - EROSION * (1.0 - clamp(detail * 2.5 - 0.75, 0.0, 1.0))) * 2.0, 0.0, 1.0);
}

/**
 * Colour of the cloud at q with `shade` of the sun's light reaching it. White, in places faintly rose or
 * blue, with a blush over what lies low: its underside and its rim. The sky lights it from above, so it is
 * dimmer low down, and thin cloud between the eye and the sun is brighter.
 */
vec3 puffColour(vec3 q, float shade, vec3 dir, vec3 lit) {
    float v = q.y / (CELL + PUFF_MAX);
    float tint = noise(vec3(q.xz * (4.0 / PERIOD), 0.93));
    vec3 albedo = mix(CLOUD_WHITE, CLOUD_ROSE, smoothstep(0.58, 0.70, tint));
    albedo = mix(albedo, CLOUD_BLUE, 1.0 - smoothstep(0.30, 0.42, tint));
    float foot = 1.0 - smoothstep(0.1, 0.55, v);
    albedo = mix(albedo, CLOUD_BLUSH, 0.65 * foot);
    float towards = max(dot(dir, uLightDir), 0.0);
    float lining = 1.0 + 0.5 * towards * towards * towards * towards;
    vec3 light = (lit - sunlight) * mix(0.5, 1.0, smoothstep(0.1, 0.8, v)) + sunlight * (0.2 + 1.3 * shade) * lining;
    // At night the cloud keeps a little light of its own, so its shape can still be told.
    return albedo * (light + NIGHT_LIGHT * uGlow) + VIOLET * 0.05 * foot * uGlow;
}

/**
 * Marches the cloud along the ray between `from` and `to`. Most samples fall in the open air between the
 * clouds and cost only the lookups of puff(); the fine noise, the tint and the shadow are read inside a cloud.
 */
void clouds(vec3 dir, float from, float to, vec3 lit) {
    int steps = min(uSteps, MAX_STEPS);
    vec3 origin = vec3(uNoiseOrigin.x + uPhase * PERIOD, CELL - uLevels.y, uNoiseOrigin.y);
    float grow = exp(log((to + STEP_ORIGIN) / (from + STEP_ORIGIN)) / float(steps));
    float at = (from + STEP_ORIGIN) * pow(grow, dither(gl_FragCoord.xy));
    float shade = 1.0;
    for (int i = 0; i < steps; i++) {
        float t = at - STEP_ORIGIN;
        float dt = at * (grow - 1.0);
        at *= grow;
        vec3 q = origin + dir * t;
        vec2 body = puff(q);
        if (body.x <= 0.0) continue;
        // Billows smaller than the samples are apart would only be grain.
        float d = billow(q, body.x, t < 100.0 ? 4 : (t < 220.0 ? 3 : 2));
        // The cloud thins out just in front of the camera instead of filling the view, and into the haze far away.
        d *= smoothstep(1.0, 5.0, t) * (1.0 - smoothstep(CELLS_NEAR, CELLS_FAR, t));
        if (d <= 0.004) continue;
        // Self-shadowing from one sample towards the light, reused once little of a sample reaches the eye.
        if (through > 0.3) shade = exp(-puff(q + uLightDir * SHADOW_REACH).x * SHADOW);
        float alpha = 1.0 - exp(-d * EXTINCTION * dt);
        gathered += through * alpha * puffColour(q, shade, dir, lit);
        reached += through * alpha * t;
        through *= 1.0 - alpha;
        if (firstCloud < 0.0) firstCloud = t;
        if (through < 0.03) return;
    }
}

/**
 * The few islands that fell, t along the ray where it leaves the box: premultiplied colour, and in w how much
 * of what is behind them they hide. They lie in layers ever further down, so they slide against each other as
 * the camera moves, and a deeper layer shows less through the dark, the last one barely. A layer is land where
 * its noise is high, which is seldom, bent by more noise so that no outline follows the noise's own grid, with
 * a finer noise for a ragged shore; the same noise read further down the ray with a higher threshold is the
 * island's shadowed side, so it narrows downward.
 */
vec4 fallenIsles(vec3 dir, float t, vec3 lit) {
    float slope = -1.0 / dir.y;
    vec2 at = dir.xz * t + uNoiseOrigin;
    vec2 run = dir.xz * slope;
    vec4 sum = vec4(0.0);
    for (int k = 0; k < ISLES; k++) {
        float depth = ISLE_DEPTH[k];
        vec2 top = (at + run * depth) / PERIOD;
        vec2 bend = ISLE_WARP * (vec2(noise(vec3(top * 5.0, 0.64)), noise(vec3(top * 5.0 + 0.47, 0.18))) - 0.5);
        float rough = noise(vec3(top * 24.0, 0.52));
        float land = isleField(top + bend, k) + 0.03 * (rough - 0.5);
        float under = isleField((at + run * depth * (1.0 + ISLE_THICK)) / PERIOD + bend, k);
        float face = smoothstep(ISLE_COVER, ISLE_COVER + ISLE_SOFT, land);
        float side = smoothstep(ISLE_COVER + 0.04, ISLE_COVER + 0.04 + ISLE_SOFT, under);
        float cover = max(face, side);
        if (cover <= 0.0) continue;
        // Daylight reaches less far down than the eye does. The top is lighter towards the island's middle.
        vec3 stone = STONE * (lit * 0.5 * exp(-depth / 200.0) + 0.06);
        float shading = mix(0.5, mix(0.8, 1.05, smoothstep(0.0, 0.08, land - ISLE_COVER)) * (0.85 + 0.3 * rough), face);
        float shows = cover * ISLE_SHOWS[k] * exp(-MURK * depth * slope) * (1.0 - sum.a);
        sum += vec4(stone * shading * shows, shows);
    }
    return sum;
}

/** A point of light at `place` within a square of side `size`, for `at` in blocks: a bright core and a faint halo. */
float point(vec2 at, float size, vec2 place, float radius) {
    vec2 offset = (fract(at / size) - mix(vec2(0.25), vec2(0.75), place)) * size;
    float r = dot(offset, offset) / (radius * radius);
    return exp(-r) + 0.12 * exp(-sqrt(r) * 0.8);
}

/**
 * The void where the ray leaves the box through its floor, t along the ray: dark, a little uneven, and in a
 * few places a nebula. Where one is, when, and in which shades is as in the blocky style, from the same slow
 * noise fields, and it drifts across the void as one. Inside: a faint glow throughout; two clouds of gas of
 * five noise octaves bent by more noise, the second lying deeper so that they slide against each other as the
 * camera moves; lanes of dust that dim them; a paler heart where both are thick; here and there soft filaments
 * in a third colour along the ridges of another noise; and stars, brighter inside the gas. It is looked up far below the plane, so it barely moves
 * as the camera does and dims at a slant. A rare place outside the nebulae holds the glint of a crystal. In
 * front of all of it lie a few fallen islands.
 */
vec3 depths(vec3 dir, float t, vec3 lit) {
    float slope = -1.0 / dir.y;         // blocks of ray per block of descent
    vec2 run = dir.xz * slope;
    vec2 still = dir.xz * t + run * NEBULA_DEPTH + uNoiseOrigin;
    float seen = exp(-MURK * NEBULA_DEPTH * slope) * (0.55 + 0.45 * uGlow);
    vec2 at = still + uPhase * PERIOD * vec2(0.0, 1.0);
    vec2 centre = at / PERIOD;
    vec3 colour = DARK * (0.6 + 0.9 * noise(vec3(centre * 3.0, 0.33)));
    float nebula = smoothstep(NEBULA_RARE, NEBULA_RARE + 0.12, noise(vec3(centre, uPhase)));
    if (nebula > 0.0) {
        float tone = (noise(vec3(centre * 2.0 + 0.19, 0.45)) - 0.3) / 0.4;
        vec2 bend = vec2(noise(vec3(centre * 2.0, 0.77)), noise(vec3(centre * 2.0 + 0.31, 0.27))) - 0.5;
        vec2 bent = centre + 2.0 * NEBULA_WARP * bend;
        float one = smoothstep(0.42, 0.72, fbm(vec3(bent * 2.0, 0.21), 5));
        float two = smoothstep(0.47, 0.77, fbm(vec3((bent + run * NEBULA_GAP / PERIOD) * 3.0 + 0.43, 0.57), 5));
        float gas = max(one, two);
        vec3 glow = nebulaHue(tone) * (0.07 + 0.55 * one) + nebulaHue(fract(tone + 0.3)) * 0.45 * two;
        glow *= 1.0 - 0.6 * smoothstep(0.45, 0.75, fbm(vec3(bent * 5.0 + 0.61, 0.15), 3));
        glow += vec3(0.80, 0.74, 1.0) * 0.35 * one * one * two;
        float ridge = 1.0 - abs(2.0 * fbm(vec3(bent * 6.0, 0.89), 3) - 1.0);
        float thread = ridge * ridge * ridge * ridge * gas * smoothstep(0.5, 0.75, noise(vec3(bent * 3.0, 0.39)));
        glow = mix(glow, mix(nebulaHue(fract(tone + 0.6)), vec3(0.86, 0.82, 1.0), 0.4) * 0.75, thread * 0.6);
        vec2 square = floor(at / NEBULA_CELL);
        vec3 random = hash3(vec3(mod(square, PERIOD / NEBULA_CELL), 2.0));
        if (random.x < NEBULA_STARS) {
            float twinkle = 0.6 + 0.4 * sin(6.2832 * (uPhase * 64.0 + random.y));
            float star = point(at, NEBULA_CELL, hash3(vec3(square, 5.0)).xy, STAR_SIZE * mix(0.6, 1.0, random.z));
            glow += vec3(0.74, 0.72, 0.98) * 1.6 * star * twinkle * (0.25 + 0.75 * gas);
        }
        colour += glow * nebula * NEBULA_STRENGTH * seen;
    }
    vec2 square = floor(still / CRYSTAL_CELL);
    if (textureLod(uNoise, vec3((square + 0.5) / NOISE_SIZE, 9.5 / NOISE_SIZE), 0.0).r < CRYSTAL_SHARE) {
        vec3 random = hash3(vec3(square, 1.0));
        float pulse = 0.6 + 0.4 * sin(6.2832 * (uPhase * 160.0 + random.x));
        colour += vec3(0.62, 0.42, 0.95) * point(still, CRYSTAL_CELL, random.yz, 0.6) * pulse * seen * (1.0 - nebula);
    }
    // The islands are above all of that and fade into it.
    vec4 fallen = fallenIsles(dir, t, lit);
    return colour * (1.0 - fallen.a) + fallen.rgb;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    // Pixels without the effect write zero rather than discarding: the half-resolution path needs their distance.
    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    float end = min(span.y, fragDistance);
    if (end <= span.x) return;

    // By day the cloud is lit by the sky and sun; the haze below it is a dim violet.
    vec3 pale;
    vec3 dusk;
    vec3 lit = cloudLight(pale, dusk);

    // The clouds, within the slab of heights that can hold them.
    if (abs(dir.y) > 1.0e-4) {
        float a = (uLevels.y - 2.0 * CELL) / dir.y;
        float b = (uLevels.y + float(LAYERS) * CELL) / dir.y;
        float from = max(min(a, b), span.x);
        // No cloud shows beyond CELLS_FAR, so it is not marched there.
        float to = min(min(max(a, b), end), CELLS_FAR);
        if (to > from) clouds(dir, from, to, lit);
    }
    float cover = 1.0 - through;

    // The void is seen where the ray leaves the box through its floor before meeting terrain.
    float floorAt = dir.y < 0.0 ? uLevels.x / dir.y : INF;
    bool open = through >= 0.03 && floorAt > 0.0 && floorAt <= end * 1.0001 + 0.01;
    vec3 below = open ? depths(dir, floorAt, lit) : vec3(0.0);
    float backed = open ? 1.0 : 0.0;

    // The haze lies among the clouds. Past them it is laid over the void or the terrain for the whole of the
    // ray, and towards the horizon it is all there is; over a cloud, only for the way to it.
    vec4 beyond = hazeAlong(dir, span.x, end, pale, dusk);
    vec3 colour = through * (beyond.rgb * beyond.a + below * backed * (1.0 - beyond.a));
    float alpha = through * (beyond.a + backed * (1.0 - beyond.a));
    float first = open ? floorAt : hazeFront(dir, span.x, end);
    if (cover > 0.004) {
        vec4 before = hazeAlong(dir, span.x, reached / cover, pale, dusk);
        colour += before.rgb * before.a * cover + gathered * (1.0 - before.a);
        alpha += cover;
        first = mix(first, firstCloud, cover);
    }
    if (alpha < 0.004) return;
    fragColor = finished(vec4(colour, alpha), first, uv, ndc, dir);
}
