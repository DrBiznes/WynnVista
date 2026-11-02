// Where fog_probe_samples.fsh leaves each of the fog probe's samples for fog_probe.fsh to add up: a texture
// COLUMNS wide whose first SKY_ROWS rows are the sky, band by band from the horizon up, ELEVATIONS rows to a
// band, and whose last ROWS rows are the measurement. A sample that saw no sky, or no terrain at the right
// distance, has a negative red. Include after scene.glsl. EffectFog.PROBE_COLUMNS and the rest in Java.

const int COLUMNS = 32;             // samples across the searched part of the view
const int ROWS = 24;                // samples of the measurement up the view
const int ELEVATIONS = 8;           // samples of the sky up each band of elevation
const int SKY_ROWS = SKY_BANDS * ELEVATIONS;
