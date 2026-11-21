package me.jamino.wynndhrangelimiter.debug;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixture runs only: saves runs of consecutive rendered frames, and flies the camera at a steady speed, so that
 * something that changes from frame to frame (a flickering edge) can be seen and measured, still or in motion.
 * {@code scripts/frame_flicker.py} reads the frames.
 *
 * <ul>
 * <li>{@code wynnvista.fixture.burst}: {@code tick:frames;tick:frames}. From each world tick, that many frames
 * in a row are saved as {@code <capturePrefix>-b<tick>-f<nn>.png}.</li>
 * <li>{@code wynnvista.fixture.fly}: {@code from-to:vx,vy,vz[,yaw,pitch];...}. Between those world ticks the
 * player moves by that many blocks a tick and turns by that many degrees a tick, interpolated over the frames
 * between.</li>
 * <li>{@code wynnvista.fixture.dumpClouds} ({@code x0,y0,x1,y1} in pixels from the top left, or anything else for
 * the whole view, which is large): with each saved frame, the shader pack's cloud layer as the effects
 * read it is saved as {@code <capturePrefix>-b<tick>-f<nn>.clouds} (red: the distance to the cloud in blocks, alpha:
 * its opacity), and the pack's own cloud buffers it was read from as {@code .pack0} and {@code .pack1}. Each file is
 * four little-endian ints (x, y, width, height of the part of the view that was read, y from the bottom) and then
 * four floats a pixel (red, green, blue, alpha), row by row. The view is the pack's whole buffer only while the
 * pack does not render at a reduced size.</li>
 * </ul>
 */
public final class FixtureBurst {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynndhrangelimiter");

    private record Flight(int from, int to, double x, double y, double z, float yaw, float pitch) {}

    private static Map<Integer, Integer> bursts;
    private static List<Flight> flights;
    private static int burstTick;
    private static int framesLeft;
    private static int frame;

    private FixtureBurst() {}

    /** Once per world tick of the fixture. */
    public static void tick(MinecraftClient client, int worldTick) {
        if (bursts == null) parse();
        Integer frames = bursts.get(worldTick);
        if (frames != null) {
            client.options.hudHidden = true;
            burstTick = worldTick;
            framesLeft = frames;
            frame = 0;
            LOGGER.info("Fixture burst at tick {}: {} frames", worldTick, frames);
        }
        if (client.player == null) return;
        for (Flight flight : flights) {
            if (worldTick >= flight.from && worldTick < flight.to) {
                client.player.setVelocity(flight.x, flight.y, flight.z);
                client.player.setYaw(client.player.getYaw() + flight.yaw);
                client.player.setPitch(client.player.getPitch() + flight.pitch);
            }
        }
    }

    /** Once per rendered frame, when the world image is finished. */
    public static void frame(MinecraftClient client) {
        if (framesLeft <= 0) return;
        String prefix = System.getProperty("wynnvista.fixture.capturePrefix", "burst");
        String name = String.format("%s-b%d-f%02d.png", prefix, burstTick, frame++);
        framesLeft--;
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1, message -> {});
    }

    /** True while the pack's cloud layer of the frame being drawn is wanted ({@link #savePixels}). */
    public static boolean wantsClouds() {
        return framesLeft > 0 && System.getProperty("wynnvista.fixture.dumpClouds") != null;
    }

    /**
     * The part of the view to save, as x0, y0, x1, y1 with y from the bottom: {@code wynnvista.fixture.dumpClouds}
     * as {@code x0,y0,x1,y1} with y from the top, or the whole view for any other value.
     */
    public static int[] cloudBox(int width, int height) {
        String[] parts = System.getProperty("wynnvista.fixture.dumpClouds", "").split(",");
        if (parts.length != 4) return new int[] {0, 0, width, height};
        return new int[] {Integer.parseInt(parts[0].trim()), height - Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[2].trim()), height - Integer.parseInt(parts[1].trim())};
    }

    /** Saves a rectangle of pixels read this frame as {@code <frame>.<kind>}; {@code rgba} holds them row by row. */
    public static void savePixels(MinecraftClient client, String kind, int x, int y, int width, int height,
                                  FloatBuffer rgba) {
        String prefix = System.getProperty("wynnvista.fixture.capturePrefix", "burst");
        Path file = client.runDirectory.toPath().resolve("screenshots")
                .resolve(String.format("%s-b%d-f%02d.%s", prefix, burstTick, frame, kind));
        ByteBuffer out = ByteBuffer.allocate(16 + width * height * 16).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(x).putInt(y).putInt(width).putInt(height);
        out.asFloatBuffer().put(rgba.duplicate().rewind());
        try {
            Files.write(file, out.array());
        } catch (IOException e) {
            LOGGER.warn("Could not save {}", file, e);
        }
    }

    private static void parse() {
        bursts = new HashMap<>();
        flights = new ArrayList<>();
        for (String entry : System.getProperty("wynnvista.fixture.burst", "").split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split(":");
            bursts.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        for (String entry : System.getProperty("wynnvista.fixture.fly", "").split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split(":");
            String[] ticks = parts[0].split("-");
            String[] speed = parts[1].split(",");
            flights.add(new Flight(Integer.parseInt(ticks[0]), Integer.parseInt(ticks[1]),
                    Double.parseDouble(speed[0]), Double.parseDouble(speed[1]), Double.parseDouble(speed[2]),
                    speed.length > 3 ? Float.parseFloat(speed[3]) : 0, speed.length > 4 ? Float.parseFloat(speed[4]) : 0));
        }
    }
}
