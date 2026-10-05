package me.jamino.wynndhrangelimiter.compat.dh.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shapes taken from the form Iris's DH terrain transform emits (see run-dh/patched_shaders when Iris debug
 * options are on): a plain "void main() {" after its injected {@code modelOffset} and {@code _vert_position}.
 * The samples are hand-written, not copied from any shader pack.
 */
class DhIrisShaderPatchTest {
    private static final String VERTEX = """
            #version 330 core

            in uvec4 irisExtra;
            in uvec4 vPosition;
            in vec4 iris_color;
            vec3 _vert_position;
            uniform vec3 modelOffset;
            void _vert_init() {
            	_vert_position = vPosition.xyz;
            }
            out vec3 playerPos;
            void main() {
            	_vert_init();
            	playerPos = modelOffset + _vert_position;
            	gl_Position = vec4(playerPos, 1.0f);
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core

            in vec3 playerPos;
            layout(location = 0) out vec4 colortex0;
            void main() {
            	colortex0 = vec4(playerPos, 1.0f);
            }
            """;

    @Test
    void vertexKeepsPackMainAndExportsCameraRelativeXzAfterIt() {
        String patched = DhIrisShaderPatch.patchVertex(VERTEX);
        assertTrue(patched.contains("void wynnvista_packMain() {"));
        assertEquals(1, count(patched, "void main()"));
        int wrapper = patched.indexOf("void main()");
        assertTrue(patched.indexOf("out vec2 wynnvistaXZ;") < wrapper);
        // the pack's main (which runs _vert_init) must run before _vert_position is read
        assertTrue(patched.indexOf("wynnvista_packMain();", wrapper) < patched.indexOf("wynnvistaXZ = modelOffset.xz", wrapper));
    }

    @Test
    void fragmentClipsBeforeRunningThePackMain() {
        String patched = DhIrisShaderPatch.patchFragment(FRAGMENT);
        assertTrue(patched.contains("uniform vec4 wynnvistaRects[8];"));
        assertTrue(patched.contains("uniform int wynnvistaRectCount = -1;"));
        assertEquals(1, count(patched, "void main()"));
        int wrapper = patched.indexOf("void main()");
        assertTrue(patched.indexOf("discard;", wrapper) > 0);
        assertTrue(patched.indexOf("discard;", wrapper) < patched.indexOf("wynnvista_packMain();", wrapper));
        assertTrue(patched.indexOf("in vec2 wynnvistaXZ;") < wrapper);
    }

    @Test
    void toleratesMainSpelledWithVoidParameterOrExtraWhitespace() {
        String patched = DhIrisShaderPatch.patchFragment(FRAGMENT.replace("void main()", "void  main ( void )"));
        assertFalse(patched.contains("main ( void )"));
        assertTrue(patched.contains("void wynnvista_packMain()"));
    }

    @Test
    void rejectsUnexpectedOrAlreadyPatchedShaders() {
        assertThrows(IllegalArgumentException.class, () -> DhIrisShaderPatch.patchVertex(FRAGMENT));
        assertThrows(IllegalArgumentException.class,
                () -> DhIrisShaderPatch.patchVertex(VERTEX.replace("uniform vec3 modelOffset;", "")));
        assertThrows(IllegalArgumentException.class,
                () -> DhIrisShaderPatch.patchVertex(VERTEX.replace("vec3 _vert_position;", "")));
        assertThrows(IllegalArgumentException.class, () -> DhIrisShaderPatch.patchFragment("void helper() {}"));
        assertThrows(IllegalArgumentException.class, () -> DhIrisShaderPatch.patchFragment(FRAGMENT + FRAGMENT));
        assertThrows(IllegalArgumentException.class,
                () -> DhIrisShaderPatch.patchFragment(DhIrisShaderPatch.patchFragment(FRAGMENT)));
        assertThrows(IllegalArgumentException.class,
                () -> DhIrisShaderPatch.patchVertex(DhIrisShaderPatch.patchVertex(VERTEX)));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }
}
