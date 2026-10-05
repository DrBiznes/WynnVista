package me.jamino.wynndhrangelimiter.compat.voxy;

/** Tracks whether the stock Voxy terrain shader pair was patched, so a half-patched pair never claims exact masking. */
public final class VoxyMaskState {
    private static volatile boolean vertexPatched;
    private static volatile boolean fragmentPatched;

    private VoxyMaskState() {}

    public static void mark(String id, boolean success) {
        if (VoxyShaderPatch.VERTEX.equals(id)) {
            vertexPatched = success;
            if (!success) fragmentPatched = false;
        } else if (VoxyShaderPatch.FRAGMENT.equals(id)) {
            fragmentPatched = success && vertexPatched;
        }
    }

    /** The vertex shader is parsed before the fragment shader; a failed vertex patch vetoes the fragment patch. */
    public static boolean vertexFailed() {
        return !vertexPatched;
    }

    public static boolean shadersPatched() {
        return vertexPatched && fragmentPatched;
    }
}
