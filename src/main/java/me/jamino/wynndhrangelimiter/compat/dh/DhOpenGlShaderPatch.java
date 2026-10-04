package me.jamino.wynndhrangelimiter.compat.dh;

/** Checked edits to DH 3.3.3's stock OpenGL terrain shaders. */
public final class DhOpenGlShaderPatch {
    public static final String VERTEX = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    public static final String FRAGMENT = "assets/distanthorizons/shaders/terrain/gl/frag.frag";

    private DhOpenGlShaderPatch() {}

    public static String patch(String path, String source) {
        if (!VERTEX.equals(path) && !FRAGMENT.equals(path)) return source;
        if (source.contains("wynnvistaCameraRelativeXZ")) {
            throw new IllegalArgumentException("DH OpenGL terrain shader already patched");
        }
        String result = source.replace("\r\n", "\n");
        if (VERTEX.equals(path)) {
            result = replaceOnce(result, "out vec3 vertexWorldPos;",
                    "out vec3 vertexWorldPos;\nout vec2 wynnvistaCameraRelativeXZ;");
            result = replaceOnce(result, "vertexWorldPos = vPosition.xyz + uModelOffset;",
                    "wynnvistaCameraRelativeXZ = vec2(vPosition.xz) + uModelOffset.xz;\n    vertexWorldPos = vPosition.xyz + uModelOffset;");
        } else {
            result = replaceOnce(result, "in vec3 vertexWorldPos;",
                    "in vec3 vertexWorldPos;\nin vec2 wynnvistaCameraRelativeXZ;");
            result = replaceOnce(result, "uniform sampler2D uBlockAtlas;",
                    "uniform sampler2D uBlockAtlas;\nuniform vec4 wynnvistaRects[8];\nuniform int wynnvistaRectCount;");
            result = replaceOnce(result, "void main()\n{\n    fragColor = vertexColor;",
                    "void main()\n{\n    if (wynnvistaRectCount >= 0) {\n        bool allowed = false;\n        for (int i = 0; i < wynnvistaRectCount; ++i) {\n            vec4 r = wynnvistaRects[i];\n            allowed = allowed || (wynnvistaCameraRelativeXZ.x >= r.x && wynnvistaCameraRelativeXZ.x < r.z\n                && wynnvistaCameraRelativeXZ.y >= r.y && wynnvistaCameraRelativeXZ.y < r.w);\n        }\n        if (!allowed) discard;\n    }\n    fragColor = vertexColor;");
        }
        return result;
    }

    private static String replaceOnce(String source, String anchor, String replacement) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalArgumentException("Unexpected DH OpenGL shader anchor: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }
}
