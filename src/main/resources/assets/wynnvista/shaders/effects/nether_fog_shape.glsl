// Shared by both lava fog styles: where the layer is, its drifting noise and its glow. Include after scene.glsl.

uniform vec3 uOrigin;         // centre of the layer's floor, camera-relative
uniform vec2 uExtent;         // radii along x and z of the ellipse where the fog ends
uniform vec2 uCore;           // radii of the ellipse inside which it has its full density and height
uniform float uThickness;
uniform vec3 uPortal;         // xy: the Nether portal's x and z from the layer's centre, z: reach of its glow
uniform float uDrift;         // phase of the drifting noise, 0..1

const float NOISE_PERIOD = 640.0;       // blocks per noise texture repeat at the base octave
const float RISE = 10.0;                // blocks over which the underside thickens
const float UNEVEN = 14.0;              // how far the underside is lifted above the floor in places

/**
 * The fog at q, a position relative to the centre of the layer's floor: x = how much fog the layer allows
 * here (0 outside it), y = soft billowing noise, z = sharp wisp noise; all 0..1.
 */
vec3 fogField(vec3 q, int octaves) {
    if (q.y <= 0.0 || q.y >= uThickness) return vec3(0.0);
    float outer = length(q.xz / uExtent);
    if (outer >= 1.0) return vec3(0.0);

    // Wisps are stretched vertically and lean along x.
    vec3 p = vec3(q.x, (q.y + q.x * 0.35) * 0.5, q.z) / NOISE_PERIOD;
    vec3 wind = vec3(uDrift);
    float first = 0.0;
    float soft = 0.0;
    float sharp = 0.0;
    float weight = 0.5;
    float total = 0.0;
    for (int i = 0; i < octaves; i++) {
        float n = noise(p + wind);
        if (i == 0) first = n;
        soft += weight * n;
        total += weight;
        weight *= 0.5;
        n *= n;
        n *= n;
        sharp += n * n;
        p *= 2.0;
        wind *= -2.0;
    }

    // Outside the core the fog disperses: 0 at the core's rim, 1 where it ends. It thins slowly at first and
    // its top sinks, so from outside it is a haze that gathers towards the middle, not a wall. The coarsest
    // noise shifts the fade and lifts the underside in places (never below the floor).
    float core = length(q.xz / uCore);
    float away = core <= 1.0 ? 0.0 : (core - 1.0) / (core - outer);
    float edge = 1.0 - smoothstep(0.0, 1.0, away + (first - 0.5) * 0.7 * min(away * 4.0, 1.0));
    float top = uThickness * mix(0.4, 1.0, edge);
    float rise = smoothstep(0.0, RISE, q.y - first * UNEVEN);
    float fall = max(1.0 - q.y / top, 0.0);
    return vec3(edge * edge * edge * rise * fall * fall, soft / total, sharp / float(octaves));
}

/** Colour of the fog's own glow at q: ember red near the lava, a dark blood red higher up. */
vec3 fogGlow(vec3 q) {
    vec3 glow = mix(vec3(0.85, 0.24, 0.06), vec3(0.45, 0.05, 0.03), smoothstep(0.0, 0.9, q.y / uThickness));
    // Around the portal the fog glows with its purple instead, strongest low down.
    float portal = 1.0 - min(length(vec3(q.x - uPortal.x, q.y * 0.6, q.z - uPortal.y)) / uPortal.z, 1.0);
    return mix(glow, vec3(0.60, 0.16, 0.95), portal * portal * (3.0 - 2.0 * portal));
}
