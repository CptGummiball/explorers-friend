package net.explorersfriend.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.marker.MapMarker;
import net.explorersfriend.marker.MarkerStore;
import net.explorersfriend.overlay.OverlayLayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayWebServiceTest {

    static OverlayLayer<MapClaim> claims;
    static OverlayLayer<MarkerStore.Item> markers;
    static OverlayWebService service;

    @BeforeAll
    static void setup() {
        claims = new OverlayLayer<>("claims");
        claims.replaceAll(List.of(new MapClaim("c1", "jsonimport", "overworld",
                List.of(new MapClaim.ClaimRect(0, 0, 511, 511)),
                "Spawn", "Admin", null, 0x554080FF, 0xFF4080FF, 1000)));
        markers = new OverlayLayer<>("markers");
        MapMarker marker = new MapMarker("m1", "overworld", "Home", "house", 10, 64, 20,
                null, null, null, null, "Alice", 1, 1, true, MapMarker.SOURCE_COMMAND, null);
        markers.replaceAll(List.of(new MarkerStore.Item(marker, false, true, null)));
        service = new OverlayWebService(MapConfig.defaults(),
                () -> Set.of("overworld"),
                claims, markers, null,
                uuid -> new byte[]{1, 2, 3},
                hash -> hash.equals("aaaaaaaaaaaaaaaa") ? new byte[]{9} : null,
                () -> "{\"worlds\":[]}".getBytes(StandardCharsets.UTF_8),
                () -> "{\"ready\":true}".getBytes(StandardCharsets.UTF_8),
                () -> "ef_up 1\n",
                List.of("jsonimport"));
    }

    private static JsonObject json(OverlayWebService.Response response) {
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void claimsEndpointDeliversItemsWithEtagAnd304() {
        OverlayWebService.Response first = service.handle("/api/v1/claims",
                Map.of("world", "overworld"), null);
        assertEquals(200, first.status());
        assertNotNull(first.etag());
        JsonObject body = json(first);
        assertEquals(1, body.getAsJsonArray("items").size());
        JsonObject claim = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("Spawn", claim.get("name").getAsString());
        assertTrue(claim.get("fill").getAsString().startsWith("#55"), "fill keeps its alpha");

        OverlayWebService.Response revalidated = service.handle("/api/v1/claims",
                Map.of("world", "overworld"), first.etag());
        assertEquals(304, revalidated.status());
    }

    @Test
    void bboxFiltersAndValidates() {
        OverlayWebService.Response hit = service.handle("/api/v1/claims",
                Map.of("world", "overworld", "bbox", "100,100,200,200"), null);
        assertEquals(1, json(hit).getAsJsonArray("items").size());
        OverlayWebService.Response miss = service.handle("/api/v1/claims",
                Map.of("world", "overworld", "bbox", "5000,5000,6000,6000"), null);
        assertEquals(0, json(miss).getAsJsonArray("items").size());
        assertEquals(400, service.handle("/api/v1/claims",
                Map.of("world", "overworld", "bbox", "not,a,box"), null).status());
        assertEquals(400, service.handle("/api/v1/claims",
                Map.of("world", "overworld", "bbox", "1,1,999999999,999999999"), null).status());
        assertEquals(400, service.handle("/api/v1/claims", Map.of("world", "../etc"), null).status());
        assertEquals(400, service.handle("/api/v1/claims", Map.of(), null).status());
    }

    @Test
    void markersEndpointHonoursPrivacyFlags() {
        JsonObject body = json(service.handle("/api/v1/markers", Map.of("world", "overworld"), null));
        JsonObject marker = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("Home", marker.get("name").getAsString());
        assertTrue(marker.has("y"), "coordinates visible when allowed");
        assertTrue(!marker.has("creator"), "creator hidden by config");
    }

    @Test
    void iconRoutesAreWhitelisted() {
        assertEquals(200, service.handle("/icons/house.svg", Map.of(), null).status());
        assertEquals(404, service.handle("/icons/evil.svg", Map.of(), null).status());
        JsonObject icons = json(service.handle("/api/v1/icons", Map.of(), null));
        assertTrue(icons.getAsJsonArray("icons").size() >= 20);
    }

    @Test
    void bannerIconAndHeadRoutes() {
        assertEquals(200, service.handle("/api/v1/banner-icons/aaaaaaaaaaaaaaaa.png", Map.of(), null).status());
        assertEquals(404, service.handle("/api/v1/banner-icons/ffffffffffffffff.png", Map.of(), null).status());
        assertEquals(404, service.handle("/api/v1/banner-icons/../secret.png", Map.of(), null).status());
        assertEquals(200, service.handle(
                "/api/v1/heads/069a79f4-44e9-4726-a5be-fca90e38aaf5.png", Map.of(), null).status());
    }

    @Test
    void overlaysListsLayersWithDefaults() {
        JsonObject overlays = json(service.handle("/api/v1/overlays", Map.of(), null));
        assertTrue(overlays.getAsJsonArray("layers").size() >= 2);
    }

    @Test
    void metricsServesPrometheusText() {
        OverlayWebService.Response metrics = service.handle("/metrics", Map.of(), null);
        assertEquals(200, metrics.status());
        assertTrue(new String(metrics.body(), StandardCharsets.UTF_8).contains("ef_up 1"));
    }

    @Test
    void disabledPlayersLayerReturnsEmptyList() {
        JsonObject players = json(service.handle("/api/v1/players", Map.of(), null));
        assertEquals(0, players.getAsJsonArray("players").size());
    }

    @Test
    void handlesRouting() {
        assertTrue(service.handles("/api/v1/claims"));
        assertTrue(service.handles("/icons/house.svg"));
        assertTrue(service.handles("/metrics"));
        assertTrue(!service.handles("/tiles/w/0/0_0.png"));
        assertEquals(404, service.handle("/api/v1/unknown", Map.of(), null).status());
    }

    @Test
    void truncationIsSignalled() {
        OverlayLayer<MapClaim> many = new OverlayLayer<>("claims");
        java.util.List<MapClaim> items = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(new MapClaim("c" + i, "jsonimport", "overworld",
                    List.of(new MapClaim.ClaimRect(i * 600, 0, i * 600 + 100, 100)),
                    null, null, null, 0x55000000, 0xFF000000, 1));
        }
        many.replaceAll(items);
        MapConfig base = MapConfig.defaults();
        MapConfig.Claims small = new MapConfig.Claims(true, true, 60, 0.3, 1.0, 2, true, true, true,
                List.of("*"), List.of(), 100, 0x4080FF);
        MapConfig config = new MapConfig(base.web(), base.render(), base.scan(), base.storage(),
                base.worlds(), base.players(), base.logging(), base.blocks(), small, base.markers(),
                base.performance());
        OverlayWebService capped = new OverlayWebService(config, () -> Set.of("overworld"),
                many, null, null, u -> null, h -> null,
                () -> new byte[0], () -> new byte[0], () -> "", List.of());
        JsonObject body = json(capped.handle("/api/v1/claims", Map.of("world", "overworld"), null));
        assertEquals(20, body.getAsJsonArray("items").size());
        assertTrue(!body.has("truncated"), "20 items fit the 100 cap");
    }
}
