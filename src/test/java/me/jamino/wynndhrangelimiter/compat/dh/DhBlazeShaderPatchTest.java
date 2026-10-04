package me.jamino.wynndhrangelimiter.compat.dh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhBlazeShaderPatchTest {
    @Test
    void patchesPinnedVertexAndFragmentPair() throws IOException {
        String vertex = patch("terrain/blaze/vert.vsh", DhBlazeShaderPatch.VERTEX);
        String fragment = patch("terrain/blaze/frag.fsh", DhBlazeShaderPatch.FRAGMENT);
        assertTrue(vertex.contains("layout(location = 7) out vec2 wynnvistaWorldXZ;"));
        assertTrue(vertex.contains("wynnvistaWorldXZ = vec2(vPosition.xz) + uModelOffset.xz;"));
        assertTrue(fragment.contains("layout(location = 7) in vec2 wynnvistaWorldXZ;"));
        assertTrue(fragment.contains("if (!allowed) discard;"));
        assertTrue(fragment.indexOf("if (!allowed) discard;") < fragment.indexOf("fragColor = vertexColor;"));
    }

    @Test
    void rejectsChangedOrRepeatedAnchors() throws IOException {
        String source = source("terrain/blaze/frag.fsh");
        assertThrows(IllegalArgumentException.class,
                () -> DhBlazeShaderPatch.patch(DhBlazeShaderPatch.FRAGMENT,
                        source.replace("fragColor = vertexColor;", "fragColor = vec4(1.0);")));
        assertThrows(IllegalArgumentException.class,
                () -> DhBlazeShaderPatch.patch(DhBlazeShaderPatch.FRAGMENT, source + source));
        assertEquals(source, DhBlazeShaderPatch.patch("other:terrain/blaze/frag", source));
    }

    private static String patch(String path, String id) throws IOException {
        return DhBlazeShaderPatch.patch(id, source(path));
    }

    private static String source(String path) throws IOException {
        String resource = "assets/distanthorizons/shaders/" + path;
        try (InputStream in = DhBlazeShaderPatchTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing pinned DH shader: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
