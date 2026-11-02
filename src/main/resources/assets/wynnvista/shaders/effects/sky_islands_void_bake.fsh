#version 330 core

// The cloud cover of every column of the blocky void's two lattices, drawn once a frame into a texel per
// column: the first lattice in rg, the second in ba. r and b: how far the column's noise is past COVER, as
// clumpCoverSteady() gives it; g and a: clumpLevel(), which says the height its clump floats at. A view ray
// then reads one texel per column it walks through instead of five noise lookups, and gets the same numbers.

#include "scene.glsl"

#include "sky_islands_void_shape.glsl"

#include "sky_islands_void_lattice.glsl"

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 column = floor(gl_FragCoord.xy);
    vec2 one = columnPlace(column, 0);
    vec2 two = columnPlace(column, 1);
    fragColor = vec4(clumpCoverSteady(one) - COVER, clumpLevel(one), clumpCoverSteady(two) - COVER, clumpLevel(two));
}
