package me.jamino.wynndhrangelimiter.compat.replaymod;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Tells which server a ReplayMod replay was recorded on. A replay is played from a file through a connection
 * that has no server entry, so the game itself reports no server, and WynnVista would treat a Wynncraft replay
 * as an unknown world: no LOD masking and no world effects, while it is watched or rendered to video.
 *
 * <p>ReplayMod is optional and not compiled against: the few accessors used are looked up by name, and a build
 * without them is logged once and treated as if no replay were playing.
 */
public final class ReplayModSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("replaymod");

    private static boolean broken;
    private static Field instance;
    private static Method getReplayHandler;
    private static Method getReplayFile;
    /** The replay last asked about and its server: the file's metadata is parsed anew on every read. */
    private static Object knownHandler;
    private static String knownServer = "";

    private ReplayModSupport() {}

    /**
     * The address of the server the replay being played was recorded on, as the server list had it then
     * ({@code play.wynncraft.com}); empty when no replay is playing or it does not say.
     */
    public static synchronized String replayServer() {
        if (!LOADED || broken) return "";
        try {
            if (instance == null) {
                Class<?> mod = Class.forName("com.replaymod.replay.ReplayModReplay");
                getReplayHandler = mod.getMethod("getReplayHandler");
                getReplayFile = Class.forName("com.replaymod.replay.ReplayHandler").getMethod("getReplayFile");
                instance = mod.getField("instance");
            }
            Object mod = instance.get(null);
            Object handler = mod == null ? null : getReplayHandler.invoke(mod);
            if (handler == null) {
                knownHandler = null;
                return "";
            }
            if (handler != knownHandler) {
                knownServer = serverOf(getReplayFile.invoke(handler));
                knownHandler = handler;
                LOGGER.info("ReplayMod replay recorded on '{}'", knownServer);
            }
            return knownServer;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            broken = true;
            LOGGER.warn("ReplayMod's replay is not readable; replays are treated as unknown worlds", e);
            return "";
        }
    }

    private static String serverOf(Object replayFile) throws ReflectiveOperationException {
        if (replayFile == null) return "";
        // Through the interfaces, which are public; the implementing classes need not be.
        Object metaData = Class.forName("com.replaymod.replaystudio.replay.ReplayFile").getMethod("getMetaData")
                .invoke(replayFile);
        if (metaData == null) return "";
        Object server = metaData.getClass().getMethod("getServerName").invoke(metaData);
        return server instanceof String name ? name : "";
    }
}
