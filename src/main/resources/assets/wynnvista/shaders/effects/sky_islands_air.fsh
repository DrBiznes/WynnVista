#version 330 core

// What rises through the air of the Sky Islands, near the camera only: white streaks of updraft in the gaps
// between the islands, violet motes under the islands, motes of light at two named places, and spray where
// a waterfall lands in the cloud. All of them are the same thing: thin upright lines, at most one per square
// of ground (more for spray), along which dashes move: long ones for a streak, cubes for a mote. The view
// ray walks the squares it passes over and meets each line exactly; nothing is marched.

#include "scene.glsl"

uniform sampler2D uTerrainMap;  // g: the column's lowest block (255: none), b: distance into the void, a: nearness of a waterfall
uniform vec4 uMap;              // xy: the map's corner, camera-relative, zw: its size in blocks
uniform float uCameraY;
uniform float uPhase;           // 0..1 over the time after which the moving patterns repeat
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;            // 1 at night, less by day
uniform vec3 uWind;             // a place of stronger updraft: x and z on the map, and its reach
uniform vec4 uStars;            // a place of star motes: x and z on the map, its height, and its reach
uniform vec4 uSparkles;         // a place of sparkles, the same

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const float SQUARE = 8.0;               // side of the square of ground that holds one line
const float RANGE = 64.0;               // blocks from the camera beyond which nothing is drawn
const int MAX_SQUARES = 20;             // squares a ray may pass over within that range
const float CYCLE = 1600.0;             // seconds in one uPhase

const float SEA = 10.0;                 // height the updrafts and motes rise from: the top of the cloud sea
const float SPRAY_LINES = 2.0;          // lines of spray a square near a waterfall may hold
const float SPRAY_NEAR = 0.35;          // nearness of a waterfall (1 at the water, 0 from 16 blocks) within which spray rises
const float SPRAY_TOP = 18.0;           // blocks above the sea that spray can reach, right at the water
const float GUST = 0.46;                // noise above which a gust blows; lower is more streaks
const float GUST_SIZE = 96.0;           // blocks across one gust
const vec3 VIOLET = vec3(0.56, 0.30, 0.98);

/** Three unrelated numbers in 0..1 for a square of ground. */
vec3 hash(vec2 square) {
    vec3 p = fract(vec3(square.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yzz) * p.zyx);
}

/** A whole number of repeats per cycle nearest to `rate` per second, so a moving pattern wraps without a jump. */
float wraps(float rate) {
    return floor(rate * CYCLE + 0.5);
}

/**
 * Where the ray (from `eye` on the map, along `dir`) passes through the upright line at `centre`: x where it
 * enters, in blocks along it, y where it leaves, z 1 when it enters by a face across x and 0 by one across z.
 * x is negative when it misses the line or meets it beyond `end`.
 */
vec3 meet(vec2 centre, float thick, vec2 eye, vec3 dir, float end) {
    vec2 d = mix(vec2(1.0e-5), dir.xz, greaterThan(abs(dir.xz), vec2(1.0e-5)));
    vec2 a = (centre - thick - eye) / d;
    vec2 b = (centre + thick - eye) / d;
    vec2 into = min(a, b);
    float near = max(max(into.x, into.y), 0.0);
    float far = min(max(a.x, b.x), max(a.y, b.y));
    return vec3(near < far && near < end ? near : -1.0, far, step(into.y, into.x));
}

/**
 * A cube on a line the ray passes through: cubes as high as the line is wide, `spacing` blocks apart, moved
 * along it by `shift` spacings. x is the shade of the face the ray enters the cube by, as a block is shaded
 * (top 1, sides 0.8 and 0.62, underside 0.5), 0 when it passes through none; y numbers the cube.
 */
vec2 cube(vec3 met, vec3 dir, float thick, float spacing, float shift) {
    float size = 2.0 * thick / spacing;
    float enter = (uCameraY + dir.y * met.x) / spacing + shift;
    float leave = (uCameraY + dir.y * met.y) / spacing + shift;
    // Entered by the side of the line at the height of a cube: one of its sides.
    if (fract(enter) < size) return vec2(met.z > 0.5 ? 0.8 : 0.62, floor(enter));
    // Otherwise the ray is inside the line already when it comes to a cube, from above or from below.
    float low = min(enter, leave);
    float high = max(enter, leave);
    if (fract(low) < size) return vec2(dir.y < 0.0 ? 1.0 : 0.5, floor(low));
    if (floor(high) > floor(low)) return vec2(dir.y < 0.0 ? 1.0 : 0.5, floor(high));
    return vec2(0.0);
}

/** Nothing right in front of the eyes, and nothing popping in at the edge of the range. */
float nearAndFar(float t) {
    return smoothstep(1.5, 5.0, t) * (1.0 - smoothstep(RANGE * 0.6, RANGE, t));
}

/**
 * One of the lines of spray of a square near a waterfall: small white cubes that rise from the cloud and
 * thin out, higher the nearer the water. Premultiplied colour and opacity.
 */
vec4 spray(vec2 square, float which, vec2 eye, vec3 dir, float end, vec3 daylight) {
    vec3 random = hash(square + which * vec2(17.3, 31.7));
    vec2 centre = (square + random.xy) * SQUARE;
    if (any(lessThan(centre, vec2(0.0))) || any(greaterThanEqual(centre, uMap.zw))) return vec4(0.0);
    vec3 met = meet(centre, 0.1, eye, dir, end);
    if (met.x < 0.0) return vec4(0.0);
    float t = met.x;
    float y = uCameraY + dir.y * t;
    if (y < SEA || y > SEA + SPRAY_TOP) return vec4(0.0);
    float nearness = texelFetch(uTerrainMap, ivec2(centre), 0).a;
    float top = SEA + SPRAY_TOP * (nearness - SPRAY_NEAR) / (1.0 - SPRAY_NEAR);
    if (y > top) return vec4(0.0);
    float spacing = 2.5 + 2.5 * random.z;
    vec2 drop = cube(met, dir, 0.1, spacing, random.x - wraps(2.2 / spacing) * uPhase);
    if (drop.x <= 0.0) return vec4(0.0);
    float alpha = 0.75 * (1.0 - (y - SEA) / (top - SEA)) * nearAndFar(t);
    return vec4(vec3(0.97, 0.98, 1.0) * (daylight + 0.1) * drop.x * alpha, alpha);
}

/**
 * The line of one square as the ray (from `eye` on the map, along `dir`) meets it before `end`: premultiplied
 * colour and opacity, zero where it shows nothing.
 */
vec4 line(vec2 square, vec2 eye, vec3 dir, float end, vec3 daylight) {
    vec3 random = hash(square);
    vec2 centre = (square + 0.2 + 0.6 * random.xy) * SQUARE;
    if (any(lessThan(centre, vec2(0.0))) || any(greaterThanEqual(centre, uMap.zw))) return vec4(0.0);

    // What kind of line this square holds, from where it is.
    float starry = 1.0 - length(centre - uStars.xy) / uStars.w;
    float sparkling = 1.0 - length(centre - uSparkles.xy) / uSparkles.w;
    bool local = max(starry, sparkling) > 0.0;
    float thick = 0.14;

    vec3 met = meet(centre, thick, eye, dir, end);
    if (met.x < 0.0) return vec4(0.0);
    float near = met.x;
    float y = uCameraY + dir.y * near;
    float fade = nearAndFar(near);

    if (local) {
        // Motes of light: small cubes that drift up slowly and come and go.
        bool stars = starry > sparkling;
        vec4 place = stars ? uStars : uSparkles;
        float inside = stars ? starry : sparkling;
        if (random.z > (stars ? 0.9 : 0.95) * smoothstep(0.0, 0.35, inside)) return vec4(0.0);
        float low = place.z - (stars ? 30.0 : 8.0);
        float high = place.z + (stars ? 55.0 : 40.0);
        if (y < low || y > high) return vec4(0.0);
        float spacing = stars ? 9.0 : 7.0;
        vec2 mote = cube(met, dir, thick, spacing, random.x - wraps((stars ? 0.35 : 0.25) / spacing) * uPhase);
        if (mote.x <= 0.0) return vec4(0.0);
        // Each mote has its own slow blink, by the cube it is.
        float blink = sin(6.2832 * (wraps(stars ? 0.22 : 0.4) * uPhase + random.y + 0.37 * mote.y));
        float lit = smoothstep(stars ? -0.2 : 0.2, 0.9, blink);
        vec3 colour;
        if (stars) {
            colour = mix(vec3(1.0, 0.93, 0.70), vec3(0.72, 0.88, 1.0), step(0.5, random.y));
        } else {
            // Pastels: rose, mint, sky and butter.
            float pick = floor(random.y * 4.0);
            colour = pick < 1.0 ? vec3(1.0, 0.72, 0.86) : pick < 2.0 ? vec3(0.70, 1.0, 0.84)
                    : pick < 3.0 ? vec3(0.70, 0.86, 1.0) : vec3(1.0, 0.95, 0.70);
        }
        float alpha = lit * fade * (0.4 + 0.4 * uGlow);
        return vec4(colour * mote.x * alpha, alpha);
    }

    vec4 map = texelFetch(uTerrainMap, ivec2(centre), 0);
    if (map.b > 0.0) {
        // Open void below: an updraft. Gusts come and go across the area; none through a waterfall.
        if (map.a > 0.3) return vec4(0.0);
        float windy = 1.0 - min(length(centre - uWind.xy) / uWind.z, 1.0);
        float gust = noise(vec3(centre / GUST_SIZE / NOISE_SIZE + uPhase * vec2(3.0, 0.0), uPhase * 4.0)) + 0.3 * windy;
        float strength = smoothstep(GUST, GUST + 0.12, gust);
        if (strength <= 0.0 || random.z > 0.6 + 0.4 * windy) return vec4(0.0);
        float top = SEA + 70.0 + 50.0 * random.x + 40.0 * windy;
        if (y < SEA || y > top) return vec4(0.0);
        float spacing = 16.0 + 12.0 * random.y;
        float along = fract(y / spacing - wraps(9.0 / spacing) * uPhase + random.x);
        float dash = 0.4;
        if (along > dash) return vec4(0.0);
        // A streak is brightest at its head and steps down along its tail; it thins out towards its top.
        float tail = (floor(along / dash * 3.0) + 1.0) / 3.0;
        float alpha = 0.55 * tail * strength * fade * (1.0 - smoothstep(top - 30.0, top, y));
        return vec4(vec3(0.97, 0.98, 1.0) * (daylight + 0.1) * alpha, alpha);
    }

    // Under an island: a violet mote now and then, rising from the cloud to the rock.
    float ceiling = map.g * 255.0;
    if (random.z > 0.45 || ceiling < SEA + 8.0 || y < SEA || y > ceiling) return vec4(0.0);
    float spacing = 12.0 + 10.0 * random.y;
    vec2 mote = cube(met, dir, thick, spacing, random.x - wraps(0.8 / spacing) * uPhase);
    if (mote.x <= 0.0) return vec4(0.0);
    float blink = 0.6 + 0.4 * sin(6.2832 * (wraps(0.3) * uPhase + random.y + 0.37 * mote.y));
    float alpha = blink * fade * (0.35 + 0.45 * uGlow);
    return vec4(VIOLET * mote.x * alpha, alpha);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    float end = min(min(span.y, fragDistance), RANGE);
    if (end <= span.x) return;

    // Streaks are lit like the cloud they rise from; motes shine by themselves.
    vec3 daylight = skyLight(uAmbient, uLightColor, 0.0) * 0.9;
    daylight += sunLight * 0.35;

    // Walk the squares of ground under the ray, nearest first.
    vec2 eye = -uMap.xy;
    vec2 at = eye + dir.xz * span.x;
    vec2 square = floor(at / SQUARE);
    vec2 way = vec2(dir.x < 0.0 ? -1.0 : 1.0, dir.z < 0.0 ? -1.0 : 1.0);
    vec2 pace = SQUARE / max(abs(dir.xz), vec2(1.0e-5));
    vec2 next = span.x + ((square + max(way, 0.0)) * SQUARE - at) * way / max(abs(dir.xz), vec2(1.0e-5));
    vec3 colour = vec3(0.0);
    float through = 1.0;
    float first = -1.0;
    float entered = span.x;
    for (int i = 0; i < MAX_SQUARES; i++) {
        float left = min(next.x, next.y);
        vec4 found = line(square, eye, dir, end, daylight);
        // Spray stays low, so its squares are only looked up where the ray is low over them.
        float lowest = uCameraY + dir.y * (dir.y < 0.0 ? min(left, end) : entered);
        float highest = uCameraY + dir.y * (dir.y < 0.0 ? entered : min(left, end));
        vec2 middle = (square + 0.5) * SQUARE;
        if (lowest < SEA + SPRAY_TOP && highest > SEA && all(greaterThanEqual(middle, vec2(0.0)))
                && all(lessThan(middle, uMap.zw)) && texelFetch(uTerrainMap, ivec2(middle), 0).a > SPRAY_NEAR - 0.3) {
            for (float which = 1.0; which <= SPRAY_LINES; which++) {
                vec4 drop = spray(square, which, eye, dir, end, daylight);
                found = vec4(found.rgb + (1.0 - found.a) * drop.rgb, 1.0 - (1.0 - found.a) * (1.0 - drop.a));
            }
        }
        if (found.a > 0.0) {
            colour += through * found.rgb;
            through *= 1.0 - found.a;
            if (first < 0.0) first = left;
        }
        if (left >= end || through < 0.05) break;
        entered = left;
        if (next.x < next.y) {
            next.x += pace.x;
            square.x += way.x;
        } else {
            next.y += pace.y;
            square.y += way.y;
        }
    }
    float alpha = 1.0 - through;
    if (alpha < 0.004) return;

    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 whole = vec4(colour, alpha);
    // Too near for haze; a shader pack's fog and a cloud in front still apply.
    fragColor = underClouds(whole, cloudAt < first ? vec4(0.0) : whole, 0.0, cloud, dir * first);
}
