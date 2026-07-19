package net.explorersfriend.overlay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayLayerTest {

    private record Item(String id, String dimensionSlug, int minX, int minZ, int maxX, int maxZ)
            implements OverlayItem {
        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            return o;
        }
    }

    @Test
    void revisionOnlyAdvancesOnRealChanges() {
        OverlayLayer<Item> layer = new OverlayLayer<>("test");
        assertEquals(0, layer.revision());
        assertTrue(layer.applyChanges(List.of(new Item("a", "w", 0, 0, 10, 10)), List.of()));
        assertEquals(1, layer.revision());
        assertFalse(layer.applyChanges(List.of(new Item("a", "w", 0, 0, 10, 10)), List.of()),
                "identical upsert must not bump the revision");
        assertEquals(1, layer.revision());
        assertTrue(layer.applyChanges(List.of(new Item("a", "w", 0, 0, 20, 10)), List.of()));
        assertEquals(2, layer.revision());
        assertTrue(layer.applyChanges(List.of(), List.of("a")));
        assertEquals(3, layer.revision());
        assertFalse(layer.applyChanges(List.of(), List.of("a")), "removing absent id is a no-op");
    }

    @Test
    void dimensionsStaySeparate() {
        OverlayLayer<Item> layer = new OverlayLayer<>("test");
        layer.applyChanges(List.of(
                new Item("a", "overworld", 0, 0, 10, 10),
                new Item("b", "nether", 0, 0, 10, 10)), List.of());
        assertEquals(1, layer.queryAll("overworld", 100).size());
        assertEquals(1, layer.queryAll("nether", 100).size());
        assertEquals(0, layer.queryAll("end", 100).size());
    }

    @Test
    void boundingBoxQueriesAreExactAndDeduplicated() {
        OverlayLayer<Item> layer = new OverlayLayer<>("test");
        layer.applyChanges(List.of(
                new Item("big", "w", -1000, -1000, 2000, 2000), // spans many cells
                new Item("small", "w", 5000, 5000, 5010, 5010),
                new Item("far", "w", 100_000, 100_000, 100_010, 100_010)), List.of());
        List<Item> hit = layer.queryBox("w", 0, 0, 100, 100, 100);
        assertEquals(1, hit.size());
        assertEquals("big", hit.get(0).id());
        assertEquals(2, layer.queryBox("w", -2000, -2000, 6000, 6000, 100).size());
        assertTrue(layer.queryBox("w", 90_000, 90_000, 90_100, 90_100, 100).isEmpty(),
                "bbox misses everything");
    }

    @Test
    void limitIsRespected() {
        OverlayLayer<Item> layer = new OverlayLayer<>("test");
        java.util.List<Item> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(new Item("m" + i, "w", i * 100, 0, i * 100 + 10, 10));
        }
        layer.replaceAll(many);
        assertEquals(10, layer.queryBox("w", -10, -10, 10_000, 10_000, 10).size());
        assertEquals(10, layer.queryAll("w", 10).size());
    }
}
