package net.explorersfriend.render;

import net.explorersfriend.world.DirtyTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileStoreAndTrackingTest {

    @Test
    void tileRoundTrip(@TempDir Path dir) throws Exception {
        TileStore store = new TileStore(dir);
        int[] pixels = new int[512 * 512];
        pixels[123] = 0xFFAA5533;
        assertTrue(store.writeTile("overworld", 0, 3, -4, pixels));
        int[] loaded = store.readTile("overworld", 0, 3, -4);
        assertArrayEquals(pixels, loaded);
    }

    @Test
    void emptyTilesAreDeletedNotWritten(@TempDir Path dir) throws Exception {
        TileStore store = new TileStore(dir);
        int[] pixels = new int[512 * 512];
        pixels[0] = 0xFF000001;
        store.writeTile("w", 0, 0, 0, pixels);
        assertTrue(Files.exists(store.tilePath("w", 0, 0, 0)));
        assertFalse(store.writeTile("w", 0, 0, 0, new int[512 * 512]), "all-transparent → delete");
        assertFalse(Files.exists(store.tilePath("w", 0, 0, 0)));
    }

    @Test
    void corruptTileReadsAsNull(@TempDir Path dir) throws Exception {
        TileStore store = new TileStore(dir);
        Path path = store.tilePath("w", 0, 1, 1);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "not a png");
        assertNull(store.readTile("w", 0, 1, 1));
    }

    @Test
    void dimensionSlugsAreFilesystemSafe() {
        assertEquals("minecraft_overworld", TileStore.dimensionSlug("minecraft:overworld"));
        assertEquals("mod_weird_dim_name", TileStore.dimensionSlug("mod:weird/dim\\name"));
        assertFalse(TileStore.dimensionSlug("a:../../etc").contains("/"));
        assertFalse(TileStore.dimensionSlug("a:..\\..\\etc").contains("\\"));
    }

    @Test
    void metaDetectsFormatChanges(@TempDir Path dir) throws Exception {
        TileStore store = new TileStore(dir);
        assertFalse(store.checkOrWriteMeta("w", 4), "first run: no valid meta yet");
        assertTrue(store.checkOrWriteMeta("w", 4), "second run: matches");
        Files.writeString(dir.resolve("w/meta.json"),
                "{\"formatVersion\": 999, \"tileSize\": 512, \"zoomLevels\": 4}");
        assertFalse(store.checkOrWriteMeta("w", 4), "version mismatch detected");
    }

    @Test
    void renderedChunksIndexRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("rendered.json");
        RenderedChunksIndex index = new RenderedChunksIndex(file);
        assertFalse(index.isRendered("w", 5, -7));
        index.markRendered("w", 5, -7);
        index.markRendered("w", -33, 64);
        assertTrue(index.isRendered("w", 5, -7));
        index.saveIfDirty();

        RenderedChunksIndex loaded = RenderedChunksIndex.load(file);
        assertTrue(loaded.isRendered("w", 5, -7));
        assertTrue(loaded.isRendered("w", -33, 64));
        assertFalse(loaded.isRendered("w", 5, -8));
        assertFalse(loaded.isRendered("other", 5, -7));
    }

    @Test
    void dirtyTrackerDebouncesAndForcesOverdue() throws Exception {
        DirtyTracker tracker = new DirtyTracker();
        tracker.markDirty("w", 1, 2);
        assertTrue(tracker.drainReady(500, 10_000, 100).isEmpty(), "still in quiet period");
        Thread.sleep(60);
        List<DirtyTracker.DirtyChunk> ready = tracker.drainReady(50, 10_000, 100);
        assertEquals(List.of(new DirtyTracker.DirtyChunk("w", 1, 2)), ready);
        assertEquals(0, tracker.size(), "drained entries are removed");

        // continuously re-marked chunk must still flush once maxDelay passes
        tracker.markDirty("w", 3, 3);
        long start = System.nanoTime();
        List<DirtyTracker.DirtyChunk> overdue = List.of();
        while (overdue.isEmpty() && System.nanoTime() - start < 2_000_000_000L) {
            tracker.markDirty("w", 3, 3); // keeps resetting the quiet period
            overdue = tracker.drainReady(10_000, 100, 10);
            Thread.sleep(20);
        }
        assertEquals(1, overdue.size(), "maxDelay must force a flush");
    }

    @Test
    void dirtyTrackerClearRemovesMark() {
        DirtyTracker tracker = new DirtyTracker();
        tracker.markDirty("w", 9, 9);
        assertTrue(tracker.isDirty("w", 9, 9));
        assertTrue(tracker.clear("w", 9, 9));
        assertFalse(tracker.isDirty("w", 9, 9));
        assertFalse(tracker.clear("w", 9, 9));
    }
}
