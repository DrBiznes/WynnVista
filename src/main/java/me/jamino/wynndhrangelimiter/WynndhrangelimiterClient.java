package me.jamino.wynndhrangelimiter;

import me.jamino.wynndhrangelimiter.debug.FixtureWorldCreator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WynndhrangelimiterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynndhrangelimiter");
    private static final WynnVistaMod MOD = new WynnVistaMod();
    private static boolean fixtureAutoOpenAttempted;
    private static int fixtureAutoOpenTicks;
    private static int fixtureWorldTicks;
    private static boolean fixtureCommandsSent;
    private static boolean fixtureScreenshotTaken;
    private static Map<Integer, String> fixtureTimeline;
    private static Set<Integer> fixtureCaptureTicks;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Wynn DH Range Limiter");

        MOD.initialize();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Player JOIN event triggered");
            MOD.onPlayerJoin(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Player DISCONNECT event triggered");
            MOD.onPlayerDisconnect();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (FixtureWorldCreator.requested()) {
                if (!FixtureWorldCreator.tick(client)) return;
            } else {
                autoOpenFixture(client);
            }
            MOD.onClientTick(client);
            autoStopFixture(client);
        });
    }

    private static void autoOpenFixture(MinecraftClient client) {
        String saveName = System.getProperty("wynnvista.fixture.autoload");
        if (fixtureAutoOpenAttempted || saveName == null) return;
        if (++fixtureAutoOpenTicks % 100 == 1) {
            LOGGER.info("Fixture autoload: tick={}, enabled={}, screen={}, loaded={}",
                    fixtureAutoOpenTicks, ModConfig.fixtureEnabled(),
                    client.currentScreen == null ? "null" : client.currentScreen.getClass().getName(),
                    client.isFinishedLoading());
        }
        if (!ModConfig.fixtureEnabled() || !(client.currentScreen instanceof TitleScreen)) return;
        var fixturePath = me.jamino.wynndhrangelimiter.visibility.WorldContextResolver.fixturePath();
        if (fixturePath == null || !saveName.equals(fixturePath.getFileName().toString())) {
            LOGGER.warn("Autoload save name differs from designated fixture; skipping");
            fixtureAutoOpenAttempted = true;
            return;
        }
        fixtureAutoOpenAttempted = true;
        LOGGER.info("Auto-opening isolated DH fixture: {}", saveName);
        client.createIntegratedServerLoader().start(saveName, () -> {});
    }

    private static void autoStopFixture(MinecraftClient client) {
        int limit = Integer.getInteger("wynnvista.fixture.autostopTicks", 0);
        if (limit <= 0 || client.world == null
                || !me.jamino.wynndhrangelimiter.visibility.WorldContextResolver.isFixture(client)) return;
        fixtureWorldTicks++;
        runFixtureTimeline(client);
        String commands = System.getProperty("wynnvista.fixture.commands");
        if (fixtureWorldTicks == 40 && !fixtureCommandsSent && commands != null) {
            fixtureCommandsSent = true;
            client.getServer().execute(() -> {
                for (String command : commands.split("\\|")) {
                    client.getServer().getCommandManager().parseAndExecute(
                            client.getServer().getCommandSource(), command.trim());
                }
            });
        }
        String screenshot = System.getProperty("wynnvista.fixture.screenshot");
        if (fixtureWorldTicks == 200 && screenshot != null) client.options.hudHidden = true;
        if (fixtureWorldTicks == 250 && !fixtureScreenshotTaken && screenshot != null) {
            fixtureScreenshotTaken = true;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, screenshot,
                    client.getFramebuffer(), 1, message -> LOGGER.info("Fixture screenshot: {}", message.getString()));
        }
        if (fixtureWorldTicks == limit) {
            LOGGER.info("Fixture smoke test completed after {} world ticks; shutting down normally", limit);
            client.scheduleStop();
        }
    }

    private static void runFixtureTimeline(MinecraftClient client) {
        if (fixtureTimeline == null) {
            fixtureTimeline = new HashMap<>();
            fixtureCaptureTicks = new HashSet<>();
            String timeline = System.getProperty("wynnvista.fixture.timeline", "");
            for (String event : timeline.split(";")) {
                if (event.isBlank()) continue;
                int separator = event.indexOf(':');
                if (separator < 1 || separator == event.length() - 1) {
                    throw new IllegalArgumentException("Invalid fixture timeline event: " + event);
                }
                int tick = Integer.parseInt(event.substring(0, separator).trim());
                if (tick < 2 || fixtureTimeline.putIfAbsent(tick, event.substring(separator + 1).trim()) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate fixture tick: " + tick);
                }
                for (int offset : new int[] {-1, 1, 2, 3, 5, 15}) {
                    fixtureCaptureTicks.add(tick + offset);
                }
            }
            LOGGER.info("Fixture timeline: {} teleports, {} capture ticks",
                    fixtureTimeline.size(), fixtureCaptureTicks.size());
        }
        String command = fixtureTimeline.get(fixtureWorldTicks);
        if (command != null) {
            LOGGER.info("Fixture timeline tick {}: {}", fixtureWorldTicks, command);
            client.getServer().execute(() -> client.getServer().getCommandManager().parseAndExecute(
                    client.getServer().getCommandSource(), command));
        }
        String prefix = System.getProperty("wynnvista.fixture.capturePrefix");
        if (prefix != null && fixtureCaptureTicks.contains(fixtureWorldTicks)) {
            client.options.hudHidden = true;
            String filename = prefix + "-t" + fixtureWorldTicks + ".png";
            ScreenshotRecorder.saveScreenshot(client.runDirectory, filename,
                    client.getFramebuffer(), 1,
                    message -> LOGGER.info("Fixture timeline capture: {}: {}",
                            filename, message.getString()));
        }
    }
}
