package me.jamino.wynndhrangelimiter.compat.dh.iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits applied to the shader sources Iris produces for Distant Horizons terrain ({@code dh_terrain},
 * {@code dh_water}, {@code dh_shadow}) after its transform pass. The transformed vertex shader always
 * declares {@code uniform vec3 modelOffset;} and {@code vec3 _vert_position;}, so the camera-relative
 * world X/Z can be exported without knowing anything about the shader pack. The pack's own {@code main}
 * is kept and wrapped, so the mask works with any pack that Iris can already run.
 */
public final class DhIrisShaderPatch {
    public static final String VARYING = "wynnvistaXZ";
    public static final String RECTS_UNIFORM = "wynnvistaRects";
    public static final String COUNT_UNIFORM = "wynnvistaRectCount";
    public static final int MAX_RECTS = 8;

    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final String WRAPPED_MAIN = "wynnvista_packMain";

    private DhIrisShaderPatch() {}

    public static String patchVertex(String source) {
        requireUnpatched(source);
        require(source, "uniform vec3 modelOffset;");
        require(source, "vec3 _vert_position;");
        String renamed = renameMain(source);
        return renamed + "\nout vec2 " + VARYING + ";\n"
                + "void main() {\n"
                + "    " + WRAPPED_MAIN + "();\n"
                + "    " + VARYING + " = modelOffset.xz + _vert_position.xz;\n"
                + "}\n";
    }

    public static String patchFragment(String source) {
        requireUnpatched(source);
        String renamed = renameMain(source);
        return renamed + "\nin vec2 " + VARYING + ";\n"
                + "uniform vec4 " + RECTS_UNIFORM + "[" + MAX_RECTS + "];\n"
                + "uniform int " + COUNT_UNIFORM + " = -1;\n"
                + "void main() {\n"
                + "    if (" + COUNT_UNIFORM + " >= 0) {\n"
                + "        bool wynnvistaAllowed = false;\n"
                + "        for (int i = 0; i < " + COUNT_UNIFORM + "; ++i) {\n"
                + "            vec4 r = " + RECTS_UNIFORM + "[i];\n"
                + "            wynnvistaAllowed = wynnvistaAllowed || (" + VARYING + ".x >= r.x && " + VARYING + ".x < r.z\n"
                + "                && " + VARYING + ".y >= r.y && " + VARYING + ".y < r.w);\n"
                + "        }\n"
                + "        if (!wynnvistaAllowed) discard;\n"
                + "    }\n"
                + "    " + WRAPPED_MAIN + "();\n"
                + "}\n";
    }

    private static String renameMain(String source) {
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("No main() in Iris DH terrain shader");
        int start = matcher.start();
        int end = matcher.end();
        if (matcher.find()) throw new IllegalArgumentException("More than one main() in Iris DH terrain shader");
        return source.substring(0, start) + "void " + WRAPPED_MAIN + "()" + source.substring(end);
    }

    private static void requireUnpatched(String source) {
        if (source.contains(VARYING) || source.contains(WRAPPED_MAIN)) {
            throw new IllegalArgumentException("Iris DH terrain shader already patched");
        }
    }

    private static void require(String source, String anchor) {
        if (!source.contains(anchor)) {
            throw new IllegalArgumentException("Unexpected Iris DH terrain vertex shader, missing: " + anchor);
        }
    }
}
