// Shared by both smoke plume styles: where the column is and how dense its smoke is. Include after scene.glsl.
// The column is described at Mount Wynn's size, in "plume space": relative to the vent, one unit being uScale
// blocks. A smaller volcano's plume is the same column scaled down, billows, rise and all.

uniform vec3 uVent;           // crater vent, camera-relative
uniform float uScale;         // blocks per unit of plume space; 1 for Mount Wynn
uniform vec3 uShape;          // x: column height, y: radius at the vent, z: radius at the top, in plume space
uniform vec2 uDrift;          // how far the top has blown downwind, in plume space
uniform float uScroll;        // units of plume space the noise pattern has risen

const float NOISE_PERIOD = 1280.0;      // units of plume space per noise texture repeat at the base octave

/** A camera-relative position in plume space. */
vec3 plumeSpace(vec3 p) {
    return (p - uVent) / uScale;
}

/**
 * Position of q, in plume space, inside the bent, widening column: x = distance from the axis as a fraction of the radius,
 * y = height as a fraction of the column, zw = offset from the axis. x >= 1 means outside.
 */
vec4 column(vec3 q) {
    float u = q.y / uShape.x;
    if (u <= 0.0 || u >= 1.0) return vec4(2.0, u, 0.0, 0.0);
    vec2 off = q.xz - uDrift * u * u;
    return vec4(length(off) / mix(uShape.y, uShape.z, pow(u, 0.85)), u, off);
}

/** Smoke density at q, a position in plume space. */
float density(vec3 q, int octaves) {
    vec4 c = column(q);
    float r = c.x;
    float u = c.y;
    if (r >= 1.0) return 0.0;

    // Smoke survives where the noise beats a threshold that rises towards the edge and with height,
    // so the outline is lumpy and the top breaks into drifting puffs instead of ending in a cap.
    float threshold = r * r * 0.62 + u * 0.16 + smoothstep(0.55, 1.0, u) * 0.5 - 0.02;
    if (threshold >= 1.0) return 0.0;

    // The pattern lives in column-local space and scrolls upward, so billows follow the bend of the column.
    // Billows are small in the narrow throat and grow with the column, as eddies do in a real plume.
    vec3 s = vec3(c.z, q.y - uScroll, c.w) / NOISE_PERIOD;
    float wide = smoothstep(0.04, 0.4, u);
    float n = 0.0;
    if (wide < 1.0) n += (1.0 - wide) * fbm(s * 4.0, octaves);
    if (wide > 0.0) n += wide * fbm(s + vec3(0.5), octaves);

    float d = smoothstep(0.0, 0.26, n - threshold);
    return d * smoothstep(0.0, 0.025, u) * mix(1.0, 0.4, u);
}
