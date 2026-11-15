package me.jamino.wynndhrangelimiter.compat.flashback;

import me.jamino.wynndhrangelimiter.compat.Reflected;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells which server a Flashback replay was recorded on. Flashback plays a replay from its own integrated
 * server, so the game reports no server entry, and WynnVista would treat a Wynncraft replay as an unknown
 * world: no LOD masking and no world effects, while it is watched or exported.
 *
 * <p>Unlike ReplayMod, Flashback does not write the server's address into the replay. So WynnVista keeps its
 * own note: while Flashback records on Wynncraft, the recording's identifier is written to
 * {@link #NOTES_FILE} with the address, and looked up again when that replay is played. A replay with no note
 * (recorded before this, or on another installation) is recognised by what Flashback did save: the name the
 * server had in the server list, Bobby's name for it, and the Distant Horizons folder.
 *
 * <p>Flashback is optional and not compiled against: the few members used are looked up by name, and a build
 * without them is logged once and treated as if nothing were recorded or played.
 */
public final class FlashbackSupport {
    /** In the config directory. One recording per line: its identifier, a tab, the server's address. */
    public static final String NOTES_FILE = "WynnVista-flashback.txt";

    /** What a replay recognised by its saved names alone is said to be recorded on. */
    public static final String GUESSED_SERVER = "play.wynncraft.com";

    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    /** Asked on first use, not when the class loads: the name rules below are used without the loader. */
    private static Boolean loaded;

    private static final String FLASHBACK = "com.moulberry.flashback.Flashback";
    private static final String RECORDER = "com.moulberry.flashback.record.Recorder";
    private static final String REPLAY_SERVER = "com.moulberry.flashback.playback.ReplayServer";
    private static final String META = "com.moulberry.flashback.record.FlashbackMeta";

    static final Reflected GET_REPLAY_SERVER = Reflected.staticMethod(FLASHBACK, "getReplayServer", REPLAY_SERVER);
    static final Reflected GET_METADATA = Reflected.method(REPLAY_SERVER, "getMetadata", META);
    static final Reflected RECORDER_FIELD = Reflected.staticField(FLASHBACK, "RECORDER", RECORDER);
    static final Reflected RECORDER_METADATA = Reflected.declaredField(RECORDER, "metadata", META);
    static final Reflected REPLAY_IDENTIFIER = Reflected.field(META, "replayIdentifier", null);
    static final Reflected WORLD_NAME = Reflected.field(META, "worldName", "java.lang.String");
    static final Reflected BOBBY_WORLD_NAME = Reflected.field(META, "bobbyWorldName", "java.lang.String");
    static final Reflected DISTANT_HORIZON_PATHS = Reflected.field(META, "distantHorizonPaths", "java.util.Map");

    /** Everything of Flashback's that is reached by name; checked against its jar by {@code ReplayModJarsTest}. */
    public static final List<Reflected> REFLECTED = List.of(GET_REPLAY_SERVER, GET_METADATA, RECORDER_FIELD,
            RECORDER_METADATA, REPLAY_IDENTIFIER, WORLD_NAME, BOBBY_WORLD_NAME, DISTANT_HORIZON_PATHS);

    private static boolean broken;
    private static Method getReplayServer;
    private static Method getMetadata;
    private static Field recorderField;
    private static Field recorderMetadata;
    private static Field replayIdentifier;
    private static Field worldName;
    private static Field bobbyWorldName;
    private static Field distantHorizonPaths;

    private static Map<String, String> notes;
    /** The replay last asked about and its server. */
    private static Object knownReplay;
    private static String knownServer = "";
    /** The recording last noted, so that the note is written once. */
    private static Object notedRecorder;

    private FlashbackSupport() {}

    /**
     * The address of the server the replay being played was recorded on ({@code play.wynncraft.com});
     * empty when no replay is playing or nothing says where it is from.
     */
    public static synchronized String replayServer() {
        if (!loaded() || broken) return "";
        try {
            lookUp();
            Object replay = getReplayServer.invoke(null);
            if (replay == null) {
                knownReplay = null;
                return "";
            }
            if (replay != knownReplay) {
                knownServer = serverOf(getMetadata.invoke(replay));
                knownReplay = replay;
                LOGGER.info("Flashback replay recorded on '{}'", knownServer);
            }
            return knownServer;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            broke(e);
            return "";
        }
    }

    /**
     * Once per client tick: while Flashback records on Wynncraft, notes the recording's identifier, so that
     * its replay is recognised later.
     */
    public static synchronized void noteRecording(MinecraftClient client) {
        if (!loaded() || broken) return;
        try {
            lookUp();
            Object recorder = recorderField.get(null);
            if (recorder == null || recorder == notedRecorder) return;
            if (client.getCurrentServerEntry() == null) return;
            String address = client.getCurrentServerEntry().address;
            if (!RegionPolicy.isWynncraftHost(address)) return;
            Object metadata = recorderMetadata.get(recorder);
            Object id = metadata == null ? null : replayIdentifier.get(metadata);
            if (id == null) return;
            notedRecorder = recorder;
            note(id.toString(), address.trim());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            broke(e);
        }
    }

    /**
     * Where a replay with no note is from, by the names Flashback saved with it: the server's name in the
     * server list, Bobby's name for the server, and the folders Distant Horizons kept its LODs in. Any of
     * them may be null or missing. {@link #GUESSED_SERVER} when one of them names Wynncraft, otherwise empty.
     */
    public static String guessServer(String worldName, String bobbyWorldName, Collection<String> dhFolders) {
        if (namesWynncraft(worldName) || namesWynncraft(bobbyWorldName)) return GUESSED_SERVER;
        if (dhFolders != null) {
            for (String folder : dhFolders) {
                if (folder == null) continue;
                // Only the server's folder and the dimension's: further up is the user's own path.
                String[] parts = folder.replace('\\', '/').split("/");
                for (int i = Math.max(0, parts.length - 2); i < parts.length; i++) {
                    if (namesWynncraft(parts[i])) return GUESSED_SERVER;
                }
            }
        }
        return "";
    }

    private static boolean loaded() {
        if (loaded == null) loaded = FabricLoader.getInstance().isModLoaded("flashback");
        return loaded;
    }

    private static boolean namesWynncraft(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("wynn");
    }

    /** The lines of the notes file as identifier to address; lines that are not a pair are skipped. */
    public static Map<String, String> parseNotes(List<String> lines) {
        Map<String, String> parsed = new HashMap<>();
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) continue;
            parsed.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
        }
        return parsed;
    }

    private static void lookUp() throws ReflectiveOperationException {
        if (getReplayServer != null) return;
        getMetadata = GET_METADATA.asMethod();
        recorderField = RECORDER_FIELD.asField();
        recorderMetadata = RECORDER_METADATA.asField();
        replayIdentifier = REPLAY_IDENTIFIER.asField();
        worldName = WORLD_NAME.asField();
        bobbyWorldName = BOBBY_WORLD_NAME.asField();
        distantHorizonPaths = DISTANT_HORIZON_PATHS.asField();
        getReplayServer = GET_REPLAY_SERVER.asMethod();
    }

    private static String serverOf(Object metadata) throws ReflectiveOperationException {
        if (metadata == null) return "";
        Object id = replayIdentifier.get(metadata);
        String noted = id == null ? null : notes().get(id.toString());
        if (noted != null) return noted;
        List<String> folders = distantHorizonPaths.get(metadata) instanceof Map<?, ?> paths
                ? paths.values().stream().map(path -> path instanceof File file ? file.getPath() : null).toList()
                : List.of();
        return guessServer(worldName.get(metadata) instanceof String name ? name : null,
                bobbyWorldName.get(metadata) instanceof String name ? name : null, folders);
    }

    private static Map<String, String> notes() {
        if (notes == null) {
            Path file = notesFile();
            try {
                notes = Files.exists(file) ? parseNotes(Files.readAllLines(file, StandardCharsets.UTF_8))
                        : new HashMap<>();
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("{} is not readable; Flashback replays are recognised by their saved names only",
                        file, e);
                notes = new HashMap<>();
            }
        }
        return notes;
    }

    private static void note(String id, String address) {
        if (address.equals(notes().put(id, address))) return;
        Path file = notesFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, id + "\t" + address + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.info("Flashback recording {} noted as recorded on '{}'", id, address);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("{} is not writable; this Flashback recording is recognised by its saved names only",
                    file, e);
        }
    }

    private static Path notesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(NOTES_FILE);
    }

    private static void broke(Throwable e) {
        broken = true;
        LOGGER.warn("Flashback's recording and replay are not readable; its replays are treated as unknown worlds", e);
    }
}
