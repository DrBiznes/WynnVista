package me.jamino.wynndhrangelimiter.compat.dh;

/** Small, checked edits to the two DH 3.3.3 Blaze terrain shaders. */
public final class DhBlazeShaderPatch {
    public static final String VERTEX = "distanthorizons:terrain/blaze/vert";
    public static final String FRAGMENT = "distanthorizons:terrain/blaze/frag";
    private static final String MARKER = "wynnvistaWorldXZ";

    private DhBlazeShaderPatch() {}

    public static String patch(String id, String source) {
        if (!VERTEX.equals(id) && !FRAGMENT.equals(id)) return source;
        if (source.contains(MARKER)) throw new IllegalArgumentException("DH terrain shader already patched");
        String result = source.replace("\r\n", "\n");
        if (VERTEX.equals(id)) {
            result = replaceOnce(result,
                    "layout(location = 5) flat out uint vTextureTileId;",
                    "layout(location = 5) flat out uint vTextureTileId;\nlayout(location = 7) out vec2 wynnvistaWorldXZ;");
            result = replaceOnce(result,
                    "vertexWorldPos = vPosition.xyz + (uModelOffset - uCameraPos);",
                    "wynnvistaWorldXZ = vec2(vPosition.xz) + uModelOffset.xz;\n    vertexWorldPos = vPosition.xyz + (uModelOffset - uCameraPos);");
        } else {
            result = replaceOnce(result,
                    "layout(location = 5) flat in uint vTextureTileId;",
                    "layout(location = 5) flat in uint vTextureTileId;\nlayout(location = 7) in vec2 wynnvistaWorldXZ;");
            result = replaceOnce(result,
                    "uniform sampler2D uBlockAtlas;",
                    "uniform sampler2D uBlockAtlas;\n\nlayout(std140) uniform WynnVistaMask {\n    vec4 wynnvistaRects[8];\n    int wynnvistaRectCount;\n};");
            result = replaceOnce(result,
                    "void main()\n{\n    fragColor = vertexColor;",
                    "void main()\n{\n    if (wynnvistaRectCount >= 0) {\n        bool allowed = false;\n        for (int i = 0; i < wynnvistaRectCount; ++i) {\n            vec4 r = wynnvistaRects[i];\n            allowed = allowed || (wynnvistaWorldXZ.x >= r.x && wynnvistaWorldXZ.x < r.z\n                && wynnvistaWorldXZ.y >= r.y && wynnvistaWorldXZ.y < r.w);\n        }\n        if (!allowed) discard;\n    }\n    fragColor = vertexColor;");
        }
        return result;
    }

    private static String replaceOnce(String source, String anchor, String replacement) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalArgumentException("Unexpected DH shader anchor: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }
}
