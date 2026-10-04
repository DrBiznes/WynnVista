package me.jamino.wynndhrangelimiter.compat.dh;

import com.seibel.distanthorizons.common.render.openGl.GlDhTerrainRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import net.fabricmc.loader.api.FabricLoader;

public final class DhOpenGlExactState {
    private static volatile boolean vertexPatched;
    private static volatile boolean fragmentPatched;
    private static volatile boolean rendered;

    private DhOpenGlExactState() {}

    public static void resetForProgramReload() { rendered = false; }

    public static void mark(String path, boolean success) {
        if (DhOpenGlShaderPatch.VERTEX.equals(path)) vertexPatched = success;
        if (DhOpenGlShaderPatch.FRAGMENT.equals(path)) fragmentPatched = success;
        if (!success) rendered = false;
    }

    public static boolean shadersPatched() {
        return vertexPatched && fragmentPatched && !FabricLoader.getInstance().isModLoaded("iris");
    }

    public static void rendered() { rendered = true; }

    public static boolean canClip() {
        return rendered && shadersPatched()
                && SingletonInjector.INSTANCE.get(IDhTerrainRenderer.class) instanceof GlDhTerrainRenderer;
    }
}
