package me.jamino.wynndhrangelimiter.compat.dh;

import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import net.fabricmc.loader.api.FabricLoader;

public final class DhBlazeExactState {
    private static volatile boolean vertexPatched;
    private static volatile boolean fragmentPatched;
    private static volatile boolean rendered;

    private DhBlazeExactState() {}

    public static void resetForShaderReload() {
        vertexPatched = false;
        fragmentPatched = false;
        rendered = false;
    }

    public static void mark(String id, boolean success) {
        if (DhBlazeShaderPatch.VERTEX.equals(id)) vertexPatched = success;
        if (DhBlazeShaderPatch.FRAGMENT.equals(id)) fragmentPatched = success;
        if (!success) rendered = false;
    }

    public static boolean shadersPatched() {
        return vertexPatched && fragmentPatched && !FabricLoader.getInstance().isModLoaded("iris");
    }

    public static void rendered() { rendered = true; }

    public static boolean canClip() {
        return rendered && shadersPatched()
                && SingletonInjector.INSTANCE.get(IDhTerrainRenderer.class) instanceof BlazeDhTerrainRenderer;
    }
}
