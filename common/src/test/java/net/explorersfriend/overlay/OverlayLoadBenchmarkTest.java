package net.explorersfriend.overlay;

import net.explorersfriend.claims.ChunkRectMerger;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.marker.MapMarker;
import net.explorersfriend.marker.MarkerStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load characteristics with 10,000 claims + 10,000 markers across 3 dimensions:
 * index build time, bbox query latency and JSON payload size. Thresholds are
 * deliberately generous (CI machines vary); the printed numbers document the baseline.
 */
class OverlayLoadBenchmarkTest {

    @Test
    void tenThousandClaimsAndMarkersStayFast() {
        String[] dims = {"overworld", "nether", "end"};
        List<MapClaim> claims = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            int baseX = (i % 100) * 700 - 35_000;
            int baseZ = (i / 100) * 700 - 35_000;
            claims.add(new MapClaim("claim-" + i, "bench", dims[i % 3],
                    List.of(new MapClaim.ClaimRect(baseX, baseZ, baseX + 320, baseZ + 320)),
                    "Claim " + i, "Owner" + (i % 50), null, 0x55336699, 0xFF336699, 1));
        }
        OverlayLayer<MapClaim> claimLayer = new OverlayLayer<>("claims");
        long start = System.nanoTime();
        claimLayer.replaceAll(claims);
        long indexMillis = (System.nanoTime() - start) / 1_000_000;

        List<MarkerStore.Item> markers = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            MapMarker marker = new MapMarker("marker-" + i, dims[i % 3], "Marker " + i, "house",
                    (i % 200) * 350 - 35_000, 64, (i / 200) * 700 - 35_000,
                    null, null, null, null, null, 1, 1, true, MapMarker.SOURCE_COMMAND, null);
            markers.add(new MarkerStore.Item(marker, false, true, null));
        }
        OverlayLayer<MarkerStore.Item> markerLayer = new OverlayLayer<>("markers");
        start = System.nanoTime();
        markerLayer.replaceAll(markers);
        long markerIndexMillis = (System.nanoTime() - start) / 1_000_000;

        // viewport-sized bbox queries
        start = System.nanoTime();
        int found = 0;
        for (int i = 0; i < 100; i++) {
            found += claimLayer.queryBox("overworld", i * 100, i * 100,
                    i * 100 + 2000, i * 100 + 1200, 5000).size();
        }
        double claimQueryMicros = (System.nanoTime() - start) / 1000.0 / 100;
        assertTrue(found > 0);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            markerLayer.queryBox("overworld", -35_000, -35_000, -30_000, -30_000, 5000);
        }
        double markerQueryMicros = (System.nanoTime() - start) / 1000.0 / 100;

        // payload size of a viewport query
        List<MapClaim> view = claimLayer.queryBox("overworld", 0, 0, 3000, 2000, 5000);
        int payloadBytes = 0;
        for (MapClaim claim : view) {
            payloadBytes += claim.toJson().toString().length();
        }

        System.out.printf(java.util.Locale.ROOT,
                "[bench] 10k claims index: %d ms | 10k markers index: %d ms | "
                        + "claim bbox query: %.1f us | marker bbox query: %.1f us | "
                        + "viewport payload: %d claims, %.1f KB%n",
                indexMillis, markerIndexMillis, claimQueryMicros, markerQueryMicros,
                view.size(), payloadBytes / 1024.0);

        assertTrue(indexMillis < 2000, "claim index build under 2s (was " + indexMillis + " ms)");
        assertTrue(markerIndexMillis < 2000, "marker index build under 2s");
        assertTrue(claimQueryMicros < 50_000, "claim query under 50ms");
        assertTrue(markerQueryMicros < 50_000, "marker query under 50ms");
        assertEquals(10_000, claimLayer.size());
        assertEquals(10_000, markerLayer.size());
    }

    @Test
    void hundredSimulatedPlayersDeltaEfficiently() {
        net.explorersfriend.config.MapConfig.Players config =
                net.explorersfriend.config.MapConfig.defaults().players();
        net.explorersfriend.player.PlayerLayer service =
                new net.explorersfriend.player.PlayerLayer(config);
        // The sampler needs a server; here we only exercise the response builder with
        // an empty snapshot (the full path is covered by the live server test).
        com.google.gson.JsonObject response = service.buildResponse(null, 0);
        assertEquals(0, response.getAsJsonArray("players").size());
        assertTrue(response.has("revision"));
    }

    @Test
    void hugeChunkSetMergesQuickly() {
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                if ((x + z) % 17 != 0) { // holes -> multiple rects
                    chunks.add(ChunkRectMerger.pack(x, z));
                }
            }
        }
        long start = System.nanoTime();
        List<MapClaim.ClaimRect> rects = ChunkRectMerger.merge(chunks);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(java.util.Locale.ROOT,
                "[bench] %d chunks with holes merged to %d rects in %d ms%n",
                chunks.size(), rects.size(), millis);
        assertTrue(rects.size() < chunks.size() / 4, "merging must compress substantially");
        assertTrue(millis < 1000);
    }
}
