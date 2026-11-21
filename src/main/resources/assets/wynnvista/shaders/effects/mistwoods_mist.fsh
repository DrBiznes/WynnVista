#version 330 core

// The canopy mist of the Mistwoods: a deck of mist that fills the woods to above their lower canopy, with the
// giant trees, the upper half of Aldwell and the citadel's wall standing out of it. It drifts down the valley
// and goes around what stands in it: the map holds that flow, so the mist slows and heaps up in front of a
// trunk or a wall, runs faster and lower past its sides and laps against it. Here and there mist that has
// gathered on a ledge of a giant tree spills over it and falls into the deck. The deck is ray-marched; the
// falls are found by walking the ray across the map from one to the next.

#include "scene.glsl"

uniform sampler2D uTerrainMap;  // two tiles, one under the other. First: rg the flow, b blocks to what stands in the deck, a the woods. Second: r a ledge's height, g blocks to the nearest lip, b where its fall ends, a the height a wall at the woods' edge holds the deck's top at
uniform vec4 uMap;              // xy: the map's corner, camera-relative, zw: the size of one tile in blocks
uniform float uCameraY;
uniform vec2 uNoiseOrigin;      // the camera's place within one repeat of the noise pattern, in blocks
uniform float uPhase;           // 0..1 over the time after which the moving patterns repeat
uniform vec3 uLightDir;         // unit direction towards the sun or moon
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;            // 1 at night, less by day
uniform vec3 uBiomeFog;         // x: how far the camera is in the fog the Mistwoods' biome lays over the screen, 0..1, y: blocks at which that fog begins, z: and at which it hides everything
uniform float uPours;           // 1 where the falls of mist are drawn

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const float DECK = 102.0;               // height of the deck's top where nothing moves it
const float FLOOR = 36.0;               // below the lowest ground of the woods
const float RISE_MAX = 24.0;            // the most the top can stand above DECK
const float BILLOW = 7.0;               // blocks the top heaves up and down by, in swells of some 64 blocks
const float BANK = 9.0;                 // blocks the mist heaps up by where the flow comes to rest against something
const float BANK_REACH = 40.0;          // blocks from it within which it does
const float LAP = 5.0;                  // blocks it climbs right at a trunk or a wall as it surges
const float LAP_REACH = 14.0;
const float DIP = 4.0;                  // blocks the top drops by where the flow runs fast
const float HELD_SWELL = 2.0;           // blocks a top that is held down by a wall still heaves by, below the wall's height
const float SINK = 34.0;                // blocks the top has sunk by where the woods end
const float SOFT = 9.0;                 // blocks below the top over which the mist reaches its full density
const float RAGGED = 11.0;              // blocks the fine noise moves the top by
const float EXTINCTION = 0.05;          // per block of full mist
const float SHADE_DEPTH = 16.0;         // blocks below the top at which the light from above is down to 1/e
const float NEAR_CLEAR = 0.2;           // share of the mist left right at the camera, so a player inside can see
const float NEAR_RANGE = 56.0;          // blocks over which it returns to full
const float FAR = 1100.0;               // blocks of ray marched at most: the woods from corner to corner
const float MAX_OPACITY = 0.96;
const int MAX_STEPS = 56;
const float STEP_ORIGIN = 20.0;         // samples are spaced evenly in log(distance + this), so near mist gets the most
const float SKIRT = 56.0;               // blocks past the map's sides over which the mist runs on and thins out

const float PERIOD = 2048.0;            // blocks per repeat of the noise; one of its cells is 64 blocks at scale 1
const vec2 WIND = vec2(-3.0, 1.0);      // noise repeats the mist drifts per uPhase: 1 block a second, down the valley
const float FLOW_SCALE = 2.0;           // the map's flow is a share of this many times the even wind
const float FLOW_TURNS = 200.0;         // times per uPhase that the mist around an obstacle is laid out anew: every 32 seconds
const float FLOW_REACH = 32.0;          // blocks the flow carries the mist in that time where it is as fast as the wind
const float SURGE = 20.0;               // noise repeats the surges against the walls change through per uPhase; a whole number
const float CHURN = 12.0;               // and the fine noise, which rises through the mist; a whole number

const float POUR_WIDTH = 2.6;           // blocks from a lip's line within which its fall is, once it has spread
const float POUR_LIP = 0.9;             // and right under the lip
const float POUR_FAR = 380.0;           // blocks from the camera at which the falls are gone
const float POUR_TOP = 180.0;           // no ledge that spills is higher
const float POUR_DENSITY = 0.3;         // extinction per block in the middle of a fall
const float POUR_REST = 6.0;            // blocks below the deck's top at which a fall has gone into it
const float POUR_SPAN = 46.0;           // blocks a fall can drop before it has thinned to nothing
const float POUR_GROUP = 2.0;           // noise repeats per PERIOD of the patches of ledge that spill together: 32 blocks across; a whole number
const float POUR_STREAK = 16.0;         // and of the streaks in a fall: 4 blocks across; a whole number
const float POUR_RARE = 0.62;           // noise above which a patch spills; higher is fewer falls
const float POUR_PACE = 5.0;            // noise repeats the patches change through per uPhase: a fall lasts about a minute
const float POUR_SPEED = 150.0;         // noise repeats the streaks in a fall drop per uPhase: 6 blocks a second
const int POUR_STEPS = 56;

const vec3 MIST = vec3(0.76, 0.90, 0.74);          // pale sage, the colour the woods' own fog has in daylight
const float FOG_FLOOR = 0.12;           // the least brightness WynnIris gives the biome's fog
const vec3 NIGHT_LIGHT = vec3(0.045, 0.06, 0.05);   // light of its own the mist keeps at night, times uGlow

/**
 * The first tile at `at`, in blocks from the map's corner; in `past` how far outside the map that is, and in
 * `held` the height the deck's top is held at there, from the second tile: 255 where nothing holds it.
 */
vec4 flowMap(vec2 at, out float past, out float held) {
    vec2 inside = clamp(at, vec2(0.5), uMap.zw - 0.5);
    vec2 tile = vec2(uMap.z, 2.0 * uMap.w);
    past = length(at - inside);
    held = textureLod(uTerrainMap, (inside + vec2(0.0, uMap.w)) / tile, 0.0).a * 255.0;
    return textureLod(uTerrainMap, inside / tile, 0.0);
}

/**
 * The mist at p, camera-relative: x its density, 0..1, y how far below the deck's top p is, in blocks.
 * `detail` is how many octaves of fine noise shape its top.
 */
vec2 mist(vec3 p, int detail) {
    float past;
    float held;
    vec4 map = flowMap(p.xz - uMap.xy, past, held);
    float woods = map.a * (1.0 - smoothstep(0.0, SKIRT, past));
    if (woods <= 0.004) return vec2(0.0);
    vec2 flow = (map.rg * 255.0 - 128.0) / 127.0 * FLOW_SCALE;
    float speed = length(flow);
    float wall = map.b * (255.0 / 4.0);

    // The mist is a pattern of noise carried along by the even wind. Around an obstacle the flow differs from
    // that wind, and there the pattern is carried on by the difference for a while and then laid out anew,
    // in two layers that take turns so that neither is seen to jump.
    vec2 place = (uNoiseOrigin + p.xz) / PERIOD - WIND * uPhase;
    vec2 astray = (flow - normalize(WIND)) * (FLOW_REACH / PERIOD);
    float turn = fract(uPhase * FLOW_TURNS);
    vec2 one = place - astray * (turn - 0.5);
    float y = uCameraY + p.y;
    vec3 fine = vec3(0.0, y / PERIOD - CHURN * uPhase / 4.0, 0.0);
    float swell = noise(vec3(one, 0.31));
    float wisp = fbm(vec3(one * 4.0, 0.0) + fine * 4.0, detail);
    if (dot(astray, astray) > 1.0e-7) {
        vec2 two = place - astray * (fract(turn + 0.5) - 0.5);
        float share = abs(2.0 * turn - 1.0);
        swell = mix(swell, noise(vec3(two, 0.31)), share);
        wisp = mix(wisp, fbm(vec3(two * 4.0, 0.0) + fine * 4.0, detail), share);
    }

    // Where the flow comes to rest in front of something the mist heaps up; right at a trunk or a wall it
    // climbs and falls back in slow surges; where it squeezes past, it runs lower.
    float still = 1.0 - smoothstep(0.25, 0.85, speed);
    float surge = noise(vec3(place * 6.0, 0.67 + SURGE * uPhase));
    float top = DECK + BILLOW * (2.0 * swell - 1.0)
            + BANK * still * (1.0 - smoothstep(0.0, BANK_REACH, wall))
            + LAP * (1.0 - smoothstep(0.0, LAP_REACH, wall)) * (0.3 + 1.4 * surge * surge)
            - DIP * smoothstep(1.1, 1.5, speed)
            - SINK * (1.0 - woods) * (1.0 - woods);
    // A wall at the woods' edge that is lower than the deck holds the mist no higher than it stands, and the
    // deck slopes down to it: the mist comes hard up to the wall and does not lie over it.
    top = min(top, held - HELD_SWELL * (2.0 - 2.0 * swell));
    float below = top - y + RAGGED * (wisp - 0.5);
    if (below <= 0.0) return vec2(0.0);
    return vec2(smoothstep(0.0, SOFT, below) * smoothstep(0.0, 0.35, woods), max(top - y, 0.0));
}

/**
 * How much of something t blocks along the ray the fog of the Mistwoods' biome leaves to be drawn. That fog is
 * the same mist, laid over the screen by the game or by WynnIris for a camera inside the woods: from its end
 * on it has hidden everything, and the mist drawn here is only what moves in front of it.
 */
float clearOfBiomeFog(float t) {
    float hidden = clamp((t - uBiomeFog.y) / max(uBiomeFog.z - uBiomeFog.y, 1.0), 0.0, 1.0);
    return 1.0 - uBiomeFog.x * hidden;
}

/**
 * The falls of mist along the ray between `from` and `to`: premultiplied colour, opacity in a, and in `first`
 * the distance to the nearest of them. The second tile of the map holds, for every column, how far the
 * nearest lip is: the ray is walked that far at a time, and step by step through a fall.
 */
vec4 pours(vec3 dir, float from, float to, vec3 lit, out float first) {
    first = -1.0;
    float run = length(dir.xz);
    // Straight up or down a fall is seen end on, a few pixels of it.
    if (run < 0.03) return vec4(0.0);
    if (abs(dir.y) > 1.0e-4) {
        float a = (DECK - POUR_REST - uCameraY) / dir.y;
        float b = (POUR_TOP - uCameraY) / dir.y;
        from = max(from, min(a, b));
        to = min(to, max(a, b));
    } else if (uCameraY < DECK - POUR_REST || uCameraY > POUR_TOP) {
        return vec4(0.0);
    }
    to = min(to, POUR_FAR);
    if (to <= from) return vec4(0.0);

    vec3 sum = vec3(0.0);
    float through = 1.0;
    float pace = 0.8 / run;
    float t = from + pace * dither(gl_FragCoord.xy + 7.0);
    vec2 tile = vec2(uMap.z, 2.0 * uMap.w);
    for (int i = 0; i < POUR_STEPS; i++) {
        if (t >= to || through < 0.04) break;
        vec2 at = dir.xz * t - uMap.xy;
        vec2 inside = clamp(at, vec2(0.5), uMap.zw - 0.5);
        float away = textureLod(uTerrainMap, (inside + vec2(0.0, uMap.w)) / tile, 0.0).g * 127.5;
        if (away > POUR_WIDTH || at != inside) {
            t += max(away - POUR_WIDTH, 0.6) / run;
            continue;
        }
        vec4 fall = texelFetch(uTerrainMap, ivec2(inside) + ivec2(0, int(uMap.w)), 0);
        float lip = fall.r * 255.0;
        float foot = max(fall.b * 255.0, DECK - POUR_REST);
        float y = uCameraY + dir.y * t;
        if (lip > 0.0 && y < lip && y > foot) {
            float fallen = lip - y;
            // Patches of ledge spill together, for a while, then stop; a fall starts at its lip and grows down.
            vec2 world = (uNoiseOrigin + dir.xz * t) / PERIOD;
            float spill = noise(vec3(world * POUR_GROUP, 0.23 + POUR_PACE * uPhase));
            float flowing = smoothstep(POUR_RARE, POUR_RARE + 0.1, spill);
            float grown = (lip - foot) * smoothstep(POUR_RARE, POUR_RARE + 0.05, spill);
            if (flowing > 0.0 && fallen < grown) {
                // Strands that drop through it, with gaps between them: a thin sheet at the lip that spreads
                // and thins as it falls.
                float streak = noise(vec3(world * POUR_STREAK, y / 256.0 + POUR_SPEED * uPhase));
                float sheet = max(1.0 - away / mix(POUR_LIP, POUR_WIDTH, smoothstep(0.0, 18.0, fallen)), 0.0);
                float body = sheet * (0.15 + 1.5 * smoothstep(0.38, 0.72, streak));
                body *= smoothstep(0.0, 2.5, fallen) * (1.0 - smoothstep(0.3 * POUR_SPAN, POUR_SPAN, fallen))
                        * (1.0 - smoothstep(grown - 6.0, grown, fallen)) * smoothstep(0.0, 4.0, y - foot);
                body *= flowing * smoothstep(4.0, 16.0, t) * (1.0 - smoothstep(0.7 * POUR_FAR, POUR_FAR, t)) * clearOfBiomeFog(t);
                float alpha = 1.0 - exp(-body * POUR_DENSITY * pace);
                if (alpha > 0.002) {
                    sum += through * alpha * lit;
                    through *= 1.0 - alpha;
                    if (first < 0.0) first = t;
                }
            }
        }
        t += pace;
    }
    return vec4(sum, 1.0 - through);
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

    // The mist is lit from above by the sky and the sun or moon. For a camera inside the woods' own fog it has
    // that fog's colour: it is the same mist.
    vec3 ambient = skyLight(uAmbient, uLightColor, max(dir.y, 0.0));
    vec3 sky = MIST * ambient * 0.95;
    vec3 sun = MIST * sunLight * 0.4;
    vec3 night = NIGHT_LIGHT * uGlow;
    // The biome's fog as it is on the screen: the game's fog colour, which WynnIris keeps from going black.
    vec3 biomeFog = uFogColor * max(1.0, FOG_FLOOR / max(luminance(uFogColor), 1.0e-4));
    float towards = max(dot(dir, uLightDir), 0.0);

    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 front = vec4(0.0);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;

    // The deck, within the slab of heights that can hold it.
    float from = span.x;
    float to = min(end, FAR);
    if (abs(dir.y) > 1.0e-4) {
        float a = (FLOOR - uCameraY) / dir.y;
        float b = (DECK + RISE_MAX - uCameraY) / dir.y;
        from = max(from, min(a, b));
        to = min(to, max(a, b));
    } else if (uCameraY < FLOOR || uCameraY > DECK + RISE_MAX) {
        to = from;
    }
    if (to > from) {
        int steps = min(uSteps, MAX_STEPS);
        float grow = exp(log((to + STEP_ORIGIN) / (from + STEP_ORIGIN)) / float(steps));
        float at = (from + STEP_ORIGIN) * pow(grow, dither(gl_FragCoord.xy));
        for (int i = 0; i < steps; i++) {
            float t = at - STEP_ORIGIN;
            float dt = at * (grow - 1.0);
            at *= grow;
            // Wisps smaller than the samples are apart would only be grain.
            vec2 here = mist(dir * t, t < 140.0 ? min(uOctaves, 3) : (t < 360.0 ? 2 : 1));
            float d = here.x * mix(NEAR_CLEAR, 1.0, smoothstep(3.0, NEAR_RANGE, t)) * clearOfBiomeFog(t);
            if (d <= 0.004) continue;
            gather(front, colour, transmittance, t, cloudAt);
            if (firstHit < 0.0) firstHit = t;
            // Light comes from above and is lost on the way down; thin mist between the eye and the sun is brighter.
            float open = exp(-here.y / SHADE_DEPTH);
            vec3 lit = sky * mix(0.5, 1.0, open) + sun * open * (1.0 + 0.6 * towards * towards * towards) + night;
            lit = mix(lit, biomeFog, uBiomeFog.x);
            float alpha = 1.0 - exp(-d * EXTINCTION * dt);
            colour += transmittance * alpha * lit;
            transmittance *= 1.0 - alpha;
            if (transmittance < 0.03) break;
        }
    }
    gather(front, colour, transmittance, INF, cloudAt);
    vec4 deck = vec4(colour, 1.0 - transmittance);

    // The falls stand above the deck: on this ray they are in front of it or behind it as a whole.
    float pourAt = -1.0;
    vec4 falls = vec4(0.0);
    if (uPours > 0.5) {
        vec3 lit = mix(sky + sun * (0.9 + 0.5 * towards * towards) + night, biomeFog, uBiomeFog.x);
        falls = pours(dir, span.x, end, lit, pourAt);
    }
    vec4 whole = deck;
    float first = firstHit;
    if (falls.a > 0.0) {
        bool before = firstHit < 0.0 || pourAt < firstHit;
        whole = before ? falls + deck * (1.0 - falls.a) : deck + falls * (1.0 - deck.a);
        float fallsFront = beforeCloud(pourAt, cloudAt);
        front = before ? falls * fallsFront + front * (1.0 - falls.a * fallsFront)
                : front + falls * fallsFront * (1.0 - deck.a);
        first = before ? pourAt : firstHit;
    }
    if (whole.a < 0.004) return;
    if (whole.a > MAX_OPACITY) {
        front *= MAX_OPACITY / whole.a;
        whole *= MAX_OPACITY / whole.a;
    }
    // Aerial perspective, a shader pack's fog, and any cloud the mist is behind.
    float haze = 1.0 - exp(-max(first, 0.0) * 0.00022);
    fragColor = underClouds(whole, front, haze, cloud, dir * max(first, 0.0));
}
