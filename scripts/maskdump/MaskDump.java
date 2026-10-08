package me.jamino.wynndhrangelimiter.debug;

import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Development only, not part of the released mod: records which blocks of one area are filled, from the
 * chunks the client has loaded on the live server, into {@code wynnvista-mask/<NAME>.wvmask}. Nothing is sent
 * anywhere. See docs/TERRAIN_MASKS.md for how to build a jar with it and what to do with the file.
 *
 * <p>File (gzip, big-endian): the magic line, then ints minX, minY, minZ, sizeX, sizeY, sizeZ, one byte per
 * chunk (1 = recorded, x then z), a bit per block for visible blocks and a bit per block for fluids. Bit
 * index is {@code (y * sizeZ + z) * sizeX + x} from the minimum corner, lowest bit of each byte first.
 */
public final class MaskDump {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-maskdump");
    private static final String MAGIC = "WVMASK1\n";

    /** Name of the area; the file and its preview are named after it. */
    private static final String NAME = "sky_islands";

    /** Chunk-aligned box around the Sky Islands (776..1468, -4928..-4427) with a margin of some 70 blocks. */
    private static final int MIN_CHUNK_X = 44, MAX_CHUNK_X = 95;
    private static final int MIN_CHUNK_Z = -313, MAX_CHUNK_Z = -274;
    private static final int CHUNKS_X = MAX_CHUNK_X - MIN_CHUNK_X + 1;
    private static final int CHUNKS_Z = MAX_CHUNK_Z - MIN_CHUNK_Z + 1;
    private static final int MIN_X = MIN_CHUNK_X * 16, MIN_Z = MIN_CHUNK_Z * 16;
    private static final int SIZE_X = CHUNKS_X * 16, SIZE_Z = CHUNKS_Z * 16;

    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int CHUNKS_PER_SCAN = 24;
    private static final int AUTOSAVE_TICKS = 20 * 60;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WynnVista mask dump writer");
        thread.setDaemon(true);
        return thread;
    });

    private static int minY, sizeY;
    private static byte[] recorded;
    private static byte[] solid;
    private static byte[] fluid;
    private static int recordedCount;
    private static boolean dirty;
    private static int ticks;
    private static int announcedStep = -1;
    private static boolean ignoreFile;

    private MaskDump() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(MaskDump::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> flush(client));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("wvmask")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(status(context.getSource().getClient())));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("save").executes(context -> {
                            save(context.getSource().getClient(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(context -> {
                            recorded = null;
                            ignoreFile = true;
                            recordedCount = 0;
                            announcedStep = -1;
                            dirty = false;
                            context.getSource().sendFeedback(Text.literal(
                                    "WynnVista mask: cleared in memory. The file is replaced at the next save."));
                            return 1;
                        }))));
        LOGGER.info("Mask dump active for {}: /wvmask, /wvmask save, /wvmask reset", NAME);
    }

    private static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null || !onWynncraft(client)) return;
        ticks++;
        if (ticks % SCAN_INTERVAL_TICKS == 0 && nearRegion(client)) scan(client, world);
        if (dirty && ticks % AUTOSAVE_TICKS == 0) save(client, false);
    }

    /** On leaving the server, so nothing recorded since the last autosave is lost. */
    private static void flush(MinecraftClient client) {
        if (dirty) save(client, false);
    }

    private static boolean onWynncraft(MinecraftClient client) {
        return client.getCurrentServerEntry() != null
                && RegionPolicy.isWynncraftHost(client.getCurrentServerEntry().address);
    }

    private static boolean nearRegion(MinecraftClient client) {
        double x = client.player.getX();
        double z = client.player.getZ();
        return x > MIN_X - 600 && x < MIN_X + SIZE_X + 600 && z > MIN_Z - 600 && z < MIN_Z + SIZE_Z + 600;
    }

    private static void scan(MinecraftClient client, ClientWorld world) {
        if (recorded == null && !allocate(client, world)) return;
        int done = 0;
        for (int cz = 0; cz < CHUNKS_Z && done < CHUNKS_PER_SCAN; cz++) {
            for (int cx = 0; cx < CHUNKS_X && done < CHUNKS_PER_SCAN; cx++) {
                if (recorded[cz * CHUNKS_X + cx] != 0) continue;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(MIN_CHUNK_X + cx, MIN_CHUNK_Z + cz);
                if (chunk == null || chunk.isEmpty()) continue;
                record(chunk, cx, cz);
                recorded[cz * CHUNKS_X + cx] = 1;
                recordedCount++;
                dirty = true;
                done++;
            }
        }
        int step = recordedCount * 20 / (CHUNKS_X * CHUNKS_Z);
        if (done > 0 && step != announcedStep) {
            announcedStep = step;
            client.player.sendMessage(Text.literal(status(client)), false);
            if (recordedCount == CHUNKS_X * CHUNKS_Z) save(client, true);
        }
    }

    private static boolean allocate(MinecraftClient client, ClientWorld world) {
        minY = world.getBottomY();
        sizeY = world.getHeight();
        long bits = (long) SIZE_X * SIZE_Z * sizeY;
        recorded = new byte[CHUNKS_X * CHUNKS_Z];
        solid = new byte[(int) ((bits + 7) / 8)];
        fluid = new byte[solid.length];
        recordedCount = 0;
        Path file = file(client);
        if (ignoreFile || !Files.isRegularFile(file)) return true;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            byte[] magic = in.readNBytes(MAGIC.length());
            int[] header = new int[6];
            for (int i = 0; i < header.length; i++) header[i] = in.readInt();
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII)) || header[0] != MIN_X
                    || header[1] != minY || header[2] != MIN_Z || header[3] != SIZE_X || header[4] != sizeY
                    || header[5] != SIZE_Z) {
                Path kept = file.resolveSibling(file.getFileName() + ".old");
                Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.warn("Existing mask has another layout; kept as {} and starting again", kept);
                return true;
            }
            in.readFully(recorded);
            in.readFully(solid);
            in.readFully(fluid);
            for (byte flag : recorded) recordedCount += flag;
            LOGGER.info("Resumed mask dump: {} of {} chunks", recordedCount, recorded.length);
        } catch (IOException e) {
            LOGGER.error("Could not read {}; starting again", file, e);
            recorded = new byte[CHUNKS_X * CHUNKS_Z];
            solid = new byte[solid.length];
            fluid = new byte[solid.length];
            recordedCount = 0;
        }
        return true;
    }

    private static void record(WorldChunk chunk, int cx, int cz) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int index = 0; index < sections.length; index++) {
            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) continue;
            int baseY = chunk.sectionIndexToCoord(index) * 16 - minY;
            if (baseY < 0 || baseY + 16 > sizeY) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    long row = ((long) (baseY + y) * SIZE_Z + cz * 16 + z) * SIZE_X + cx * 16;
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        long bit = row + x;
                        // Barriers, light blocks and structure voids are invisible, as is a fluid's own block.
                        if (state.getRenderType() != BlockRenderType.INVISIBLE) {
                            solid[(int) (bit >>> 3)] |= (byte) (1 << (bit & 7));
                        }
                        if (!state.getFluidState().isEmpty()) {
                            fluid[(int) (bit >>> 3)] |= (byte) (1 << (bit & 7));
                        }
                    }
                }
            }
        }
    }

    private static String status(MinecraftClient client) {
        if (recorded == null) {
            return "WynnVista mask: nothing recorded yet. Fly over the area on Wynncraft.";
        }
        int total = CHUNKS_X * CHUNKS_Z;
        StringBuilder text = new StringBuilder("WynnVista mask: " + recordedCount + " / " + total + " chunks ("
                + recordedCount * 100 / total + "%)");
        if (recordedCount < total && client.player != null) {
            double bestDistance = Double.MAX_VALUE;
            int bestX = 0, bestZ = 0;
            for (int cz = 0; cz < CHUNKS_Z; cz++) {
                for (int cx = 0; cx < CHUNKS_X; cx++) {
                    if (recorded[cz * CHUNKS_X + cx] != 0) continue;
                    int x = MIN_X + cx * 16 + 8;
                    int z = MIN_Z + cz * 16 + 8;
                    double distance = Math.hypot(x - client.player.getX(), z - client.player.getZ());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
            text.append(". Nearest missing chunk: ").append(bestX).append(", ").append(bestZ)
                    .append(" (").append((int) bestDistance).append(" blocks away)");
        } else if (recordedCount == total) {
            text.append(". Complete.");
        }
        return text.toString();
    }

    private static Path file(MinecraftClient client) {
        return client.runDirectory.toPath().resolve("wynnvista-mask").resolve(NAME + ".wvmask");
    }

    private static void save(MinecraftClient client, boolean announce) {
        if (recorded == null) {
            if (announce && client.player != null) client.player.sendMessage(Text.literal(status(client)), false);
            return;
        }
        Path file = file(client);
        byte[] flags = recorded.clone();
        byte[] blocks = solid.clone();
        byte[] fluids = fluid.clone();
        int bottom = minY, height = sizeY, count = recordedCount;
        dirty = false;
        WRITER.execute(() -> {
            String result;
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                        new GZIPOutputStream(Files.newOutputStream(temporary)), 1 << 16))) {
                    out.write(MAGIC.getBytes(StandardCharsets.US_ASCII));
                    for (int value : new int[] {MIN_X, bottom, MIN_Z, SIZE_X, height, SIZE_Z}) out.writeInt(value);
                    out.write(flags);
                    out.write(blocks);
                    out.write(fluids);
                }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                ImageIO.write(preview(flags, blocks, height), "png",
                        file.resolveSibling(NAME + "_preview.png").toFile());
                result = "WynnVista mask: saved " + count + " chunks to " + file;
                LOGGER.info(result);
            } catch (IOException | RuntimeException e) {
                result = "WynnVista mask: save failed, see the log (" + e + ")";
                LOGGER.error("Could not write {}", file, e);
            }
            if (announce) {
                String message = result;
                client.execute(() -> {
                    if (client.player != null) client.player.sendMessage(Text.literal(message), false);
                });
            }
        });
    }

    /** Top-down picture: height of the highest block as brightness, black for the void, red where nothing is recorded. */
    private static BufferedImage preview(byte[] flags, byte[] blocks, int height) {
        BufferedImage image = new BufferedImage(SIZE_X, SIZE_Z, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < SIZE_Z; z++) {
            for (int x = 0; x < SIZE_X; x++) {
                if (flags[(z >> 4) * CHUNKS_X + (x >> 4)] == 0) {
                    image.setRGB(x, z, 0x802020);
                    continue;
                }
                int top = -1;
                for (int y = height - 1; y >= 0; y--) {
                    long bit = ((long) y * SIZE_Z + z) * SIZE_X + x;
                    if ((blocks[(int) (bit >>> 3)] & (1 << (bit & 7))) != 0) {
                        top = y;
                        break;
                    }
                }
                int grey = top < 0 ? 0 : 40 + top * 215 / (height - 1);
                image.setRGB(x, z, grey << 16 | grey << 8 | grey);
            }
        }
        return image;
    }
}
