package me.jamino.wynndhrangelimiter.compat.flashback;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlashbackSupportTest {
    private static final String WYNN = FlashbackSupport.GUESSED_SERVER;

    @Test
    void serverListNameOrBobbyNameNamesWynncraft() {
        assertEquals(WYNN, FlashbackSupport.guessServer("Wynncraft", null, null));
        assertEquals(WYNN, FlashbackSupport.guessServer("my WYNN server", null, List.of()));
        assertEquals(WYNN, FlashbackSupport.guessServer(null, "play.wynncraft.com", null));
        assertEquals("", FlashbackSupport.guessServer(null, null, null));
        assertEquals("", FlashbackSupport.guessServer("Hypixel", "mc.hypixel.net", List.of()));
    }

    @Test
    void distantHorizonsFolderCountsOnlyByItsLastTwoNames() {
        assertEquals(WYNN, FlashbackSupport.guessServer(null, null,
                List.of("C:\\mc\\Distant_Horizons_server_data\\Wynncraft\\overworld")));
        assertEquals(WYNN, FlashbackSupport.guessServer(null, null,
                List.of("/home/a/.minecraft/Distant_Horizons_server_data/play.wynncraft.com/overworld")));
        assertEquals("", FlashbackSupport.guessServer(null, null,
                List.of("C:\\Users\\wynnfan\\mc\\Distant_Horizons_server_data\\Hypixel\\overworld")));
        assertEquals("", FlashbackSupport.guessServer(null, null, Arrays.asList((String) null)));
    }

    @Test
    void notesAreIdentifierTabAddress() {
        Map<String, String> notes = FlashbackSupport.parseNotes(List.of(
                "6f1c0c3e-0000-4000-8000-000000000001\tplay.wynncraft.com",
                "", "no tab here", "\tno id", "no address\t",
                "6f1c0c3e-0000-4000-8000-000000000002\tlobby.wynncraft.com:25565 "));
        assertEquals(2, notes.size());
        assertEquals("play.wynncraft.com", notes.get("6f1c0c3e-0000-4000-8000-000000000001"));
        assertEquals("lobby.wynncraft.com:25565", notes.get("6f1c0c3e-0000-4000-8000-000000000002"));
    }
}
