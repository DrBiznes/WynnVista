package me.jamino.wynndhrangelimiter.compat.voxy;

/**
 * Checked edits to Voxy 0.2.16-beta's stock terrain shaders, applied to the import-expanded
 * sources returned by {@code ShaderLoader.parse}. The mask is evaluated on the quad corner position
 * relative to {@code baseSectionPos * 32}, before MVP, TAA offset or any other transform.
 */
public final class VoxyShaderPatch {
    public static final String VERTEX = "voxy:lod/gl46/quads3.vert";
    public static final String FRAGMENT = "voxy:lod/gl46/quads.frag";
    public static final String VARYING = "wynnvistaWorldXZ";
    public static final String RECTS_UNIFORM = "wynnvistaRects";
    public static final String COUNT_UNIFORM = "wynnvistaRectCount";
    public static final int MAX_RECTS = 8;

    private static final String VARYING_LOCATION = "layout(location = 5)";

    private VoxyShaderPatch() {}

    public static String patch(String id, String source) {
        if (!VERTEX.equals(id) && !FRAGMENT.equals(id)) return source;
        if (source.contains(VARYING)) {
            throw new IllegalArgumentException("Voxy terrain shader already patched: " + id);
        }
        if (VERTEX.equals(id)) {
            String result = replaceOnce(source, "vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {",
                    VARYING_LOCATION + " out vec2 " + VARYING + ";\n"
                            + "vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {");
            return replaceOnce(result, "vec4 pos = MVP * vec4(point, 1.0f);",
                    VARYING + " = point.xz;\n    vec4 pos = MVP * vec4(point, 1.0f);");
        }
        String result = replaceOnce(source, "layout(location = 0) in flat uvec4 interData;",
                "layout(location = 0) in flat uvec4 interData;\n"
                        + VARYING_LOCATION + " in vec2 " + VARYING + ";\n"
                        + "uniform vec4 " + RECTS_UNIFORM + "[" + MAX_RECTS + "];\n"
                        + "uniform int " + COUNT_UNIFORM + " = -1;");
        String clip = "    if (" + COUNT_UNIFORM + " >= 0) {\n"
                + "        bool wynnvistaAllowed = false;\n"
                + "        for (int i = 0; i < " + COUNT_UNIFORM + "; ++i) {\n"
                + "            vec4 r = " + RECTS_UNIFORM + "[i];\n"
                + "            wynnvistaAllowed = wynnvistaAllowed || (" + VARYING + ".x >= r.x && " + VARYING + ".x < r.z\n"
                + "                && " + VARYING + ".y >= r.y && " + VARYING + ".y < r.w);\n"
                + "        }\n"
                + "        if (!wynnvistaAllowed) {\n"
                + "            discard;\n"
                + "            return;\n"
                + "        }\n"
                + "    }\n";
        String tileCheck = "    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {";
        return replaceOnce(result, tileCheck, clip + tileCheck);
    }

    private static String replaceOnce(String source, String anchor, String replacement) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalArgumentException("Unexpected Voxy shader anchor: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }
}
