package net.explorersfriend.marker;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.overlay.OverlayLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerStoreTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static MapMarker marker(String id, String name, UUID creator) {
        long now = 1000;
        return new MapMarker(id, "overworld", name, "house", 10, 64, 20, null, null, null,
                creator, "Alice", now, now, true, MapMarker.SOURCE_COMMAND, null);
    }

    private static MarkerStore store(Path dir, MapConfig.Markers config) {
        return new MarkerStore(dir.resolve("markers.json"), new OverlayLayer<>("markers"), config);
    }

    @Test
    void addPersistReloadRoundTrip(@TempDir Path dir) {
        MapConfig.Markers config = MapConfig.defaults().markers();
        MarkerStore store = store(dir, config);
        store.load();
        assertNull(store.add(marker("m1", "My Base with spaces", ALICE)));
        store.saveIfDirty();

        MarkerStore reloaded = store(dir, config);
        reloaded.load();
        assertEquals(1, reloaded.count(), "markers survive restarts");
        assertEquals("My Base with spaces", reloaded.byId("m1").orElseThrow().name());
    }

    @Test
    void limitsAreEnforced(@TempDir Path dir) {
        MapConfig.Markers config = new MapConfig.Markers(true, true, true, 2, 3, true, true,
                true, false, true, List.of(), 30, new MapConfig.CustomIcons(true, 64, 128, 256));
        MarkerStore store = store(dir, config);
        store.load();
        assertNull(store.add(marker("a1", "One", ALICE)));
        assertNull(store.add(marker("a2", "Two", ALICE)));
        String perPlayer = store.add(marker("a3", "Three", ALICE));
        assertNotNull(perPlayer, "per-player limit enforced");
        assertNull(store.add(marker("b1", "Bob1", BOB)));
        String total = store.add(marker("b2", "Bob2", BOB));
        assertNotNull(total, "server-wide limit enforced");
    }

    @Test
    void validationRejectsBadValues(@TempDir Path dir) {
        MarkerStore store = store(dir, MapConfig.defaults().markers());
        store.load();
        assertNotNull(store.add(marker("x", "", ALICE)), "empty names rejected");
        assertNotNull(store.add(marker("x", "a".repeat(100), ALICE)), "over-long names rejected");
        MapMarker outOfWorld = new MapMarker("x", "w", "ok", "house", 40_000_000, 64, 0,
                null, null, null, ALICE, "Alice", 1, 1, true, MapMarker.SOURCE_COMMAND, null);
        assertNotNull(store.add(outOfWorld), "positions outside the world rejected");
    }

    @Test
    void unknownIconFallsBackOnLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("markers.json");
        Files.writeString(file, """
                {"schemaVersion":1,"markers":[{"id":"m","world":"w","name":"X","icon":"totally_unknown",
                 "x":1,"y":2,"z":3,"created":1,"updated":1,"visible":true,"source":"command"}]}
                """);
        MarkerStore store = store(dir, MapConfig.defaults().markers());
        store.load();
        assertEquals(IconLibrary.FALLBACK, store.byId("m").orElseThrow().icon());
    }

    @Test
    void corruptFileFallsBackToBackupThenQuarantines(@TempDir Path dir) throws Exception {
        MapConfig.Markers config = MapConfig.defaults().markers();
        MarkerStore store = store(dir, config);
        store.load();
        store.add(marker("m1", "Kept", ALICE));
        store.saveIfDirty();
        store.add(marker("m2", "Second", ALICE));
        store.saveIfDirty(); // now markers.json has 2, .bak has 1

        Files.writeString(dir.resolve("markers.json"), "{broken!!");
        MarkerStore recovered = store(dir, config);
        recovered.load();
        assertEquals(1, recovered.count(), "backup restored after corruption");
        assertTrue(recovered.byId("m1").isPresent());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")),
                    "corrupt file quarantined for inspection");
        }
    }

    @Test
    void updateAndRemove(@TempDir Path dir) {
        MarkerStore store = store(dir, MapConfig.defaults().markers());
        store.load();
        store.add(marker("m1", "Old Name", ALICE));
        assertNull(store.update("m1", m -> new MapMarker(m.id(), m.dimensionSlug(), "New Name",
                m.icon(), m.x(), m.y(), m.z(), m.description(), m.category(), m.colorRgb(),
                m.creator(), m.creatorName(), m.createdAtEpochMs(), 2000, m.visible(),
                m.source(), m.bannerDesign())));
        assertEquals("New Name", store.byId("m1").orElseThrow().name());
        assertTrue(store.remove("m1"));
        assertFalse(store.remove("m1"));
    }

    @Test
    void resolveByNameNeedsUniqueness(@TempDir Path dir) {
        MarkerStore store = store(dir, MapConfig.defaults().markers());
        store.load();
        store.add(marker("m1", "Duplicate", ALICE));
        store.add(marker("m2", "Duplicate", BOB));
        store.add(marker("m3", "Unique", ALICE));
        assertTrue(store.resolve("Unique").isPresent());
        assertTrue(store.resolve("m1").isPresent(), "id always resolves");
        assertFalse(store.resolve("Duplicate").isPresent(), "ambiguous names do not resolve");
    }

    @Test
    void bannerIdsAreStableAndPositionScoped() {
        assertEquals("banner:overworld:1:64:2", MapMarker.bannerId("overworld", 1, 64, 2));
        assertEquals(MapMarker.bannerId("overworld", 1, 64, 2), MapMarker.bannerId("overworld", 1, 64, 2));
        // two banners with the same NAME but different positions stay separate markers
        org.junit.jupiter.api.Assertions.assertNotEquals(
                MapMarker.bannerId("overworld", 1, 64, 2), MapMarker.bannerId("overworld", 1, 64, 3));
    }

    @Test
    void bannerUpsertIsIdempotent(@TempDir Path dir) {
        MarkerStore store = store(dir, MapConfig.defaults().markers());
        store.load();
        MapMarker banner = new MapMarker(MapMarker.bannerId("w", 1, 64, 2), "w", "Home", "banner",
                1, 64, 2, null, "banner", null, null, null, 1, 1, true,
                MapMarker.SOURCE_BANNER, "base=red;stripe_top:white");
        assertTrue(store.upsertBanner(banner), "first placement registers");
        assertFalse(store.upsertBanner(banner), "chunk reload re-registration is a no-op");
        assertEquals(1, store.count());
    }
}
