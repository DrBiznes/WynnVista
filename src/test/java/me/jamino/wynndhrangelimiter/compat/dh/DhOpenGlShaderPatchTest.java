package me.jamino.wynndhrangelimiter.compat.dh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhOpenGlShaderPatchTest {
    @Test
    void patchesPinnedTerrainPairBeforeWarpAndColorOutput() throws IOException {
        String vertex = patch(DhOpenGlShaderPatch.VERTEX);
        String fragment = patch(DhOpenGlShaderPatch.FRAGMENT);
        assertTrue(vertex.indexOf("wynnvistaCameraRelativeXZ =")
                < vertex.indexOf("vertexWorldPos.x += mx;"));
        assertTrue(fragment.contains("uniform vec4 wynnvistaRects[8];"));
        assertTrue(fragment.indexOf("if (!allowed) discard;")
                < fragment.indexOf("fragColor = vertexColor;"));
    }

    @Test
    void rejectsAlteredInputsAndDuplicateAnchors() throws IOException {
        String source = source(DhOpenGlShaderPatch.FRAGMENT);
        assertThrows(IllegalArgumentException.class,
                () -> DhOpenGlShaderPatch.patch(DhOpenGlShaderPatch.FRAGMENT,
                        source.replace("fragColor = vertexColor;", "fragColor = vec4(1.0);")));
        assertThrows(IllegalArgumentException.class,
                () -> DhOpenGlShaderPatch.patch(DhOpenGlShaderPatch.FRAGMENT, source + source));
        assertEquals(source, DhOpenGlShaderPatch.patch("other/path", source));
    }

    private static String patch(String path) throws IOException {
        return DhOpenGlShaderPatch.patch(path, source(path));
    }

    private static String source(String path) throws IOException {
        try (InputStream in = DhOpenGlShaderPatchTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing pinned DH shader: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
