package me.jamino.wynndhrangelimiter.compat.replaymod;

import me.jamino.wynndhrangelimiter.compat.Reflected;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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
    private static final String MOD = "com.replaymod.replay.ReplayModReplay";
    private static final String HANDLER = "com.replaymod.replay.ReplayHandler";
    private static final String REPLAY_FILE = "com.replaymod.replaystudio.replay.ReplayFile";
    private static final String META_DATA = "com.replaymod.replaystudio.replay.ReplayMetaData";

    static final Reflected INSTANCE = Reflected.staticField(MOD, "instance", MOD);
    static final Reflected GET_REPLAY_HANDLER = Reflected.method(MOD, "getReplayHandler", HANDLER);
    static final Reflected GET_REPLAY_FILE = Reflected.method(HANDLER, "getReplayFile", REPLAY_FILE);
    // Through the interface, which is public; the implementing classes need not be.
    static final Reflected GET_META_DATA = Reflected.method(REPLAY_FILE, "getMetaData", META_DATA);
    static final Reflected GET_SERVER_NAME = Reflected.method(META_DATA, "getServerName", "java.lang.String");

    /** Everything of ReplayMod's that is reached by name; checked against its jar by {@code ReplayModJarsTest}. */
    public static final List<Reflected> REFLECTED = List.of(INSTANCE, GET_REPLAY_HANDLER, GET_REPLAY_FILE,
            GET_META_DATA, GET_SERVER_NAME);

    /** Asked on first use, not when the class loads: the list above is read without the loader. */
    private static Boolean loaded;
    private static boolean broken;
    private static Field instance;
    private static Method getReplayHandler;
    private static Method getReplayFile;
    private static Method getMetaData;
    private static Method getServerName;
    /** The replay last asked about and its server: the file's metadata is parsed anew on every read. */
    private static Object knownHandler;
    private static String knownServer = "";

    private ReplayModSupport() {}

    /**
     * The address of the server the replay being played was recorded on, as the server list had it then
     * ({@code play.wynncraft.com}); empty when no replay is playing or it does not say.
     */
    public static synchronized String replayServer() {
        if (loaded == null) loaded = FabricLoader.getInstance().isModLoaded("replaymod");
        if (!loaded || broken) return "";
        try {
            if (instance == null) {
                getReplayHandler = GET_REPLAY_HANDLER.asMethod();
                getReplayFile = GET_REPLAY_FILE.asMethod();
                getMetaData = GET_META_DATA.asMethod();
                getServerName = GET_SERVER_NAME.asMethod();
                instance = INSTANCE.asField();
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
        Object metaData = getMetaData.invoke(replayFile);
        if (metaData == null) return "";
        Object server = getServerName.invoke(metaData);
        return server instanceof String name ? name : "";
    }
}
