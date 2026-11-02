// The two lattices of columns the blocky style's cuboids sit in, shared by that style and the bake of their
// cover (sky_islands_void_bake.fsh). Include after sky_islands_void_shape.glsl.

const float PUFF = 8.0;                 // side of one column of a lattice, which holds at most one cuboid
const vec3 PUFF_SHIFT = vec3(4.5, 2.5, 4.5);    // the second lattice against the first; no two faces share a plane
const float COLUMNS = PERIOD / PUFF;    // columns of a lattice per repeat of the noise, each way: SkyIslandsVoid.COLUMNS

/** Where column `column` (0 to COLUMNS each way) of lattice `set` reads the cloud's noise, in noise repeats. */
vec2 columnPlace(vec2 column, int set) {
    return ((column + 0.5) * PUFF + (set == 0 ? vec2(0.0) : PUFF_SHIFT.xz)) / PERIOD;
}
