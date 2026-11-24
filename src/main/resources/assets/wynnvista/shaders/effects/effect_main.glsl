// Included at the end of each world effect's shader, which defines effect(): the march of one pixel, ended at
// sceneDistance(), written to fragColor and fragDistance.
//
// Under a shader pack that tells how two surfaces share a pixel (sceneEdge() in scene.glsl), a pixel on the edge
// of terrain is marched once for each and the two are mixed by their shares, and any other pixel is marched to
// the distance the pack has resolved for it. Without such a pack every pixel is marched once, as the depth
// buffers have it.

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    float near;
    float far;
    float nearShare;
    bool edge = false;
    if (uEdges > 0.5) {
        edge = sceneEdge(uv, uv * 2.0 - 1.0, near, far, nearShare);
        sceneOverride = near;
    }
    effect();
    if (!edge) return;
    vec4 before = fragColor;
    resetMarch();
    sceneOverride = far;
    effect();
    fragColor = mix(fragColor, before, nearShare);
}
