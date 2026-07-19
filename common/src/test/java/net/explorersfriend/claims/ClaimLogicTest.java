package net.explorersfriend.claims;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.overlay.OverlayLayer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimLogicTest {

    @Test
    void rectMergerCollapsesRowsAndColumns() {
        Set<Long> square = new HashSet<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                square.add(ChunkRectMerger.pack(x, z));
            }
        }
        List<MapClaim.ClaimRect> rects = ChunkRectMerger.merge(square);
        assertEquals(1, rects.size(), "4x4 chunk square merges into one rect");
        assertEquals(new MapClaim.ClaimRect(0, 0, 63, 63), rects.get(0));
    }

    @Test
    void rectMergerKeepsDisjointAreasSeparate() {
        Set<Long> chunks = Set.of(
                ChunkRectMerger.pack(0, 0), ChunkRectMerger.pack(1, 0),
                ChunkRectMerger.pack(10, 10));
        List<MapClaim.ClaimRect> rects = ChunkRectMerger.merge(chunks);
        assertEquals(2, rects.size(), "enclaves stay separate rectangles");
    }

    @Test
    void rectMergerHandlesLShape() {
        Set<Long> lShape = Set.of(
                ChunkRectMerger.pack(0, 0), ChunkRectMerger.pack(1, 0),
                ChunkRectMerger.pack(0, 1));
        List<MapClaim.ClaimRect> rects = ChunkRectMerger.merge(lShape);
        assertEquals(2, rects.size());
        int covered = 0;
        for (MapClaim.ClaimRect rect : rects) {
            covered += ((rect.maxX() - rect.minX() + 1) / 16) * ((rect.maxZ() - rect.minZ() + 1) / 16);
        }
        assertEquals(3, covered, "no chunk lost or duplicated");
    }

    @Test
    void colorPriorityChain() {
        assertEquals(0x112233, ClaimColors.resolveBase(0x112233, 0x445566, 0x778899, "key", 0xABCDEF));
        assertEquals(0x445566, ClaimColors.resolveBase(null, 0x445566, 0x778899, "key", 0xABCDEF));
        assertEquals(0x778899, ClaimColors.resolveBase(null, null, 0x778899, "key", 0xABCDEF));
        int deterministic = ClaimColors.resolveBase(null, null, null, "team-alpha", 0xABCDEF);
        assertEquals(deterministic, ClaimColors.resolveBase(null, null, null, "team-alpha", 0xABCDEF),
                "same key -> same color across calls (and restarts)");
        assertNotEquals(deterministic, ClaimColors.resolveBase(null, null, null, "team-beta", 0xABCDEF));
        assertEquals(0xABCDEF, ClaimColors.resolveBase(null, null, null, "", 0xABCDEF));
    }

    @Test
    void fillIsSemiTransparentBorderIsOpaque() {
        int fill = ClaimColors.fill(0x4080FF, 0.35);
        int border = ClaimColors.border(0x4080FF);
        int fillAlpha = fill >>> 24;
        assertTrue(fillAlpha > 0 && fillAlpha < 255, "fill must be semi-transparent");
        assertEquals(0xFF, border >>> 24, "border must be fully opaque");
        assertEquals(0x4080FF, border & 0xFFFFFF);
        // opacity is clamped away from full cover
        assertTrue((ClaimColors.fill(0x4080FF, 1.0) >>> 24) < 255);
    }

    private static ClaimManager manager(OverlayLayer<MapClaim> layer) {
        MapConfig.Claims config = MapConfig.defaults().claims();
        return new ClaimManager(layer, List.of(), config, Runnable::run, null,
                dimensionId -> dimensionId.equals("minecraft:overworld") ? "minecraft_overworld" : null);
    }

    private static ClaimProvider.RawArea area(String key, String dim, Set<Long> chunks) {
        return ClaimProvider.RawArea.ofChunks(key, dim, chunks, "Base", "Alice", "Builders", null, false);
    }

    @Test
    void publishDetectsAddedChangedRemoved() {
        OverlayLayer<MapClaim> layer = new OverlayLayer<>("claims");
        ClaimManager manager = manager(layer);

        manager.publishRawForTest("test", List.of(
                area("a", "minecraft:overworld", Set.of(ChunkRectMerger.pack(0, 0))),
                area("b", "minecraft:overworld", Set.of(ChunkRectMerger.pack(5, 5)))));
        assertEquals(2, layer.size());
        long firstRevision = layer.revision();

        // change area a, drop area b
        manager.publishRawForTest("test", List.of(
                area("a", "minecraft:overworld",
                        Set.of(ChunkRectMerger.pack(0, 0), ChunkRectMerger.pack(1, 0)))));
        assertEquals(1, layer.size(), "removed claim is gone");
        assertTrue(layer.revision() > firstRevision);
    }

    @Test
    void hiddenAndUnknownDimensionsAreFiltered() {
        OverlayLayer<MapClaim> layer = new OverlayLayer<>("claims");
        ClaimManager manager = manager(layer);
        manager.publishRawForTest("test", List.of(
                new ClaimProvider.RawArea("hidden", "minecraft:overworld",
                        Set.of(ChunkRectMerger.pack(0, 0)), null, null, "Ghost", null, null, true),
                area("elsewhere", "othermod:void", Set.of(ChunkRectMerger.pack(0, 0)))));
        assertEquals(0, layer.size(), "hidden claims and unmapped dimensions never reach the layer");
    }

    @Test
    void explicitRectsSkipChunkConversion() {
        OverlayLayer<MapClaim> layer = new OverlayLayer<>("claims");
        ClaimManager manager = manager(layer);
        manager.publishRawForTest("test", List.of(new ClaimProvider.RawArea(
                "big", "minecraft:overworld", Set.of(),
                List.of(new MapClaim.ClaimRect(-10_000, -10_000, 10_000, 10_000)),
                "Huge", null, null, 0x123456, false)));
        assertEquals(1, layer.size());
        MapClaim claim = layer.queryAll("minecraft_overworld", 10).get(0);
        assertEquals(1, claim.rects().size(), "large region stays one rectangle");
        assertEquals(0x123456, claim.borderArgb() & 0xFFFFFF, "explicit provider color wins");
    }

    @Test
    void privacyConfigStripsFields() {
        MapConfig.Claims privacyConfig = new MapConfig.Claims(true, true, 60, 0.3, 1.0, 2,
                false, false, false, List.of("*"), List.of(), 5000, 0x4080FF);
        OverlayLayer<MapClaim> layer = new OverlayLayer<>("claims");
        ClaimManager manager = new ClaimManager(layer, List.of(), privacyConfig, Runnable::run, null,
                id -> "minecraft_overworld");
        manager.publishRawForTest("test", List.of(area("a", "minecraft:overworld",
                Set.of(ChunkRectMerger.pack(0, 0)))));
        MapClaim claim = layer.queryAll("minecraft_overworld", 10).get(0);
        com.google.gson.JsonObject json = claim.toJson();
        assertTrue(!json.has("owner") && !json.has("name") && !json.has("team"),
                "privacy switches strip owner/name/team");
    }
}
