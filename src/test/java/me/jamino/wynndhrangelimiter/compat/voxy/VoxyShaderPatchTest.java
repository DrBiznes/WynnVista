package me.jamino.wynndhrangelimiter.compat.voxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxyShaderPatchTest {
    private static final Pattern IMPORT = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    @Test
    void patchesPinnedVertexShaderBeforeMvpAndTaa() throws IOException {
        String source = expanded(VoxyShaderPatch.VERTEX);
        String vertex = VoxyShaderPatch.patch(VoxyShaderPatch.VERTEX, source);
        int declaration = vertex.indexOf("layout(location = 5) out vec2 wynnvistaWorldXZ;");
        int assignment = vertex.indexOf("wynnvistaWorldXZ = point.xz;");
        assertTrue(declaration > 0);
        assertTrue(declaration < vertex.indexOf("vec4 getQuadCornerPos("));
        assertTrue(assignment > declaration);
        assertTrue(assignment < vertex.indexOf("vec4 pos = MVP * vec4(point, 1.0f);"));
        assertTrue(vertex.indexOf("vec4 pos = MVP") < vertex.indexOf("pos.xy += taaOffset*pos.w;"));
    }

    @Test
    void patchesPinnedFragmentShaderAfterDerivativesAndBeforeOutputs() throws IOException {
        String fragment = VoxyShaderPatch.patch(VoxyShaderPatch.FRAGMENT, expanded(VoxyShaderPatch.FRAGMENT));
        assertTrue(fragment.contains("layout(location = 5) in vec2 wynnvistaWorldXZ;"));
        assertTrue(fragment.contains("uniform vec4 wynnvistaRects[8];"));
        assertTrue(fragment.contains("uniform int wynnvistaRectCount = -1;"));
        int clip = fragment.indexOf("if (!wynnvistaAllowed) {");
        assertTrue(fragment.indexOf("colour = textureGrad(blockModelAtlas, texPos, dx, dy);") < clip);
        assertTrue(clip < fragment.indexOf("if (DEPTH_SCALAR_COMPARE("));
        assertTrue(clip < fragment.indexOf("voxy_emitFragment(VoxyFragmentParameters("));
        assertTrue(clip < fragment.indexOf("outColour = colour;"));
    }

    @Test
    void rejectsAlteredInputsDuplicateAnchorsAndRepatching() throws IOException {
        String fragment = expanded(VoxyShaderPatch.FRAGMENT);
        String vertex = expanded(VoxyShaderPatch.VERTEX);
        assertThrows(IllegalArgumentException.class, () -> VoxyShaderPatch.patch(VoxyShaderPatch.FRAGMENT,
                fragment.replace("layout(location = 0) in flat uvec4 interData;", "")));
        assertThrows(IllegalArgumentException.class,
                () -> VoxyShaderPatch.patch(VoxyShaderPatch.FRAGMENT, fragment + fragment));
        assertThrows(IllegalArgumentException.class, () -> VoxyShaderPatch.patch(VoxyShaderPatch.VERTEX,
                vertex.replace("vec4 pos = MVP * vec4(point, 1.0f);", "vec4 pos = vec4(point, 1.0f);")));
        assertThrows(IllegalArgumentException.class, () -> VoxyShaderPatch.patch(VoxyShaderPatch.VERTEX,
                VoxyShaderPatch.patch(VoxyShaderPatch.VERTEX, vertex)));
        assertEquals(fragment, VoxyShaderPatch.patch("voxy:lod/gl46/cmdgen.comp", fragment));
    }

    @Test
    void varyingLocationIsFreeInStockShaders() throws IOException {
        for (String id : List.of(VoxyShaderPatch.VERTEX, VoxyShaderPatch.FRAGMENT)) {
            String source = expanded(id);
            assertTrue(!source.contains("location = 5"), id + " already uses varying location 5");
        }
    }

    /** Mirrors Voxy's ShaderLoader import expansion (version line dropped, imports inlined in order). */
    private static String expanded(String id) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("#version 460 core");
        expand(id, out);
        return String.join("\n", out);
    }

    private static void expand(String id, List<String> out) throws IOException {
        int colon = id.indexOf(':');
        String path = "/assets/" + id.substring(0, colon) + "/shaders/" + id.substring(colon + 1);
        try (InputStream in = VoxyShaderPatchTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing pinned Voxy shader: " + path);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.startsWith("#version")) continue;
                if (line.startsWith("#import")) {
                    Matcher match = IMPORT.matcher(line);
                    if (!match.matches()) throw new IOException("Unknown import: " + line);
                    expand(match.group("namespace") + ":" + match.group("path"), out);
                } else {
                    out.add(line);
                }
            }
        }
    }
}
