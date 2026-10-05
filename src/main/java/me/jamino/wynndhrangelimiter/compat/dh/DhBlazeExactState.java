package me.jamino.wynndhrangelimiter.compat.dh;

import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;

public final class DhBlazeExactState {
    private static volatile boolean vertexPatched;
    private static volatile boolean fragmentPatched;
    private static final long FRESH_NANOS = 2_000_000_000L;
    private static volatile long renderedNanos;

    private DhBlazeExactState() {}

    public static void resetForShaderReload() {
        vertexPatched = false;
        fragmentPatched = false;
        renderedNanos = 0;
    }

    public static void mark(String id, boolean success) {
        if (DhBlazeShaderPatch.VERTEX.equals(id)) vertexPatched = success;
        if (DhBlazeShaderPatch.FRAGMENT.equals(id)) fragmentPatched = success;
        if (!success) renderedNanos = 0;
    }

    public static boolean shadersPatched() {
        return vertexPatched && fragmentPatched;
    }

    public static void rendered() { renderedNanos = System.nanoTime(); }

    public static boolean canClip() {
        return renderedNanos != 0 && System.nanoTime() - renderedNanos < FRESH_NANOS && shadersPatched()
                && SingletonInjector.INSTANCE.get(IDhTerrainRenderer.class) instanceof BlazeDhTerrainRenderer;
    }
}
