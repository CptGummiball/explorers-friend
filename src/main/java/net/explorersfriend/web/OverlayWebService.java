package net.explorersfriend.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.marker.IconLibrary;
import net.explorersfriend.marker.MarkerStore;
import net.explorersfriend.overlay.OverlayItem;
import net.explorersfriend.overlay.OverlayLayer;
import net.explorersfriend.world.LivePlayerService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versioned overlay API ({@code /api/v1/*}, {@code /icons/*}, {@code /metrics}).
 * Pure request→response logic over immutable snapshots — no Minecraft objects, fully
 * unit-testable. Validation is strict: worlds must be known slugs, bounding boxes are
 * bounded integers, icon ids come from the fixed library, hashes/uuids are
 * format-checked. Errors are terse JSON without stack traces or internal paths.
 */
public final class OverlayWebService {

    public record Response(int status, String contentType, byte[] body,
                           String cacheControl, String etag, boolean compressible) {

        static Response json(int status, String body) {
            return new Response(status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), "no-store", null, true);
        }

        static Response error(int status, String message) {
            return json(status, "{\"error\":\"" + message + "\"}");
        }

        static Response notModified(String etag, String cacheControl) {
            return new Response(304, "application/json", new byte[0], cacheControl, etag, false);
        }
    }

    private static final Pattern BANNER_ICON = Pattern.compile("/api/v1/banner-icons/([0-9a-f]{16})\\.png");
    private static final Pattern HEAD = Pattern.compile("/api/v1/heads/([0-9a-fA-F-]{32,36})\\.png");
    private static final Pattern ICON = Pattern.compile("/icons/([a-z0-9_]{1,32})\\.svg");
    private static final int MAX_BBOX_SPAN = 1 << 24;

    private final MapConfig config;
    private final Supplier<Set<String>> knownSlugs;
    private final OverlayLayer<MapClaim> claimsLayer;         // null = claims disabled
    private final OverlayLayer<MarkerStore.Item> markersLayer; // null = markers disabled
    private final LivePlayerService players;                   // null = players disabled
    private final Function<String, byte[]> headResolver;
    private final Function<String, byte[]> bannerIconResolver;
    private final Supplier<byte[]> worldsJson;
    private final Supplier<byte[]> statusJson;
    private final Supplier<String> metricsText;
    private final List<String> claimProviderIds;
    private final Map<String, byte[]> iconSvgs = new HashMap<>();

    public OverlayWebService(MapConfig config,
                             Supplier<Set<String>> knownSlugs,
                             OverlayLayer<MapClaim> claimsLayer,
                             OverlayLayer<MarkerStore.Item> markersLayer,
                             LivePlayerService players,
                             Function<String, byte[]> headResolver,
                             Function<String, byte[]> bannerIconResolver,
                             Supplier<byte[]> worldsJson,
                             Supplier<byte[]> statusJson,
                             Supplier<String> metricsText,
                             List<String> claimProviderIds) {
        this.config = config;
        this.knownSlugs = knownSlugs;
        this.claimsLayer = claimsLayer;
        this.markersLayer = markersLayer;
        this.players = players;
        this.headResolver = headResolver;
        this.bannerIconResolver = bannerIconResolver;
        this.worldsJson = worldsJson;
        this.statusJson = statusJson;
        this.metricsText = metricsText;
        this.claimProviderIds = claimProviderIds;
        for (String icon : IconLibrary.ICONS) {
            try (InputStream in = OverlayWebService.class.getClassLoader()
                    .getResourceAsStream(IconLibrary.resourcePath(icon))) {
                if (in != null) {
                    iconSvgs.put(icon, in.readAllBytes());
                }
            } catch (IOException ignored) {
                // icon missing from the jar: served as 404
            }
        }
    }

    public boolean handles(String path) {
        return path.startsWith("/api/v1/") || path.startsWith("/icons/") || path.equals("/metrics");
    }

    public Response handle(String path, Map<String, String> query, String ifNoneMatch) {
        return switch (path) {
            case "/api/v1/worlds" -> new Response(200, "application/json; charset=utf-8",
                    worldsJson.get(), "no-cache", null, true);
            case "/api/v1/status" -> new Response(200, "application/json; charset=utf-8",
                    statusJson.get(), "no-store", null, true);
            case "/api/v1/overlays" -> overlays();
            case "/api/v1/players" -> playersResponse(query, ifNoneMatch);
            case "/api/v1/claims" -> layerResponse("claims", claimsLayer, query, ifNoneMatch,
                    config.claims().maxClaimsPerResponse());
            case "/api/v1/markers" -> layerResponse("markers", markersLayer, query, ifNoneMatch, 10_000);
            case "/api/v1/icons" -> icons();
            case "/metrics" -> config.web().metricsEnabled()
                    ? new Response(200, "text/plain; version=0.0.4; charset=utf-8",
                    metricsText.get().getBytes(StandardCharsets.UTF_8), "no-store", null, true)
                    : Response.error(404, "metrics disabled");
            default -> patternRoutes(path, ifNoneMatch);
        };
    }

    private Response patternRoutes(String path, String ifNoneMatch) {
        Matcher banner = BANNER_ICON.matcher(path);
        if (banner.matches()) {
            byte[] png = bannerIconResolver.apply(banner.group(1));
            if (png == null) {
                return Response.error(404, "unknown banner design");
            }
            return new Response(200, "image/png", png, "max-age=604800, immutable", null, false);
        }
        Matcher head = HEAD.matcher(path);
        if (head.matches()) {
            byte[] png = headResolver.apply(head.group(1));
            return new Response(200, "image/png", png, "max-age=3600", null, false);
        }
        Matcher icon = ICON.matcher(path);
        if (icon.matches()) {
            byte[] svg = iconSvgs.get(icon.group(1));
            if (svg == null) {
                return Response.error(404, "unknown icon");
            }
            return new Response(200, "image/svg+xml", svg, "max-age=86400", null, true);
        }
        return Response.error(404, "not found");
    }

    private Response overlays() {
        JsonArray layers = new JsonArray();
        if (claimsLayer != null) {
            JsonObject claims = new JsonObject();
            claims.addProperty("id", "claims");
            claims.addProperty("defaultVisible", config.claims().defaultVisibleInUi());
            JsonArray providers = new JsonArray();
            claimProviderIds.forEach(providers::add);
            claims.add("providers", providers);
            claims.addProperty("fillOpacity", config.claims().fillOpacity());
            claims.addProperty("borderWidth", config.claims().borderWidth());
            layers.add(claims);
        }
        if (markersLayer != null) {
            JsonObject markers = new JsonObject();
            markers.addProperty("id", "markers");
            markers.addProperty("defaultVisible", config.markers().defaultVisibleInUi());
            layers.add(markers);
            JsonObject banners = new JsonObject();
            banners.addProperty("id", "banner-markers");
            banners.addProperty("defaultVisible", config.markers().bannersDefaultVisibleInUi());
            layers.add(banners);
        }
        if (players != null) {
            JsonObject playerLayer = new JsonObject();
            playerLayer.addProperty("id", "players");
            playerLayer.addProperty("defaultVisible", config.players().defaultVisibleInUi());
            playerLayer.addProperty("showNames", config.players().showNames());
            layers.add(playerLayer);
        }
        JsonObject root = new JsonObject();
        root.add("layers", layers);
        return Response.json(200, root.toString());
    }

    private Response icons() {
        JsonObject root = new JsonObject();
        JsonArray icons = new JsonArray();
        IconLibrary.ICONS.forEach(icons::add);
        root.add("icons", icons);
        return new Response(200, "application/json; charset=utf-8",
                root.toString().getBytes(StandardCharsets.UTF_8), "max-age=3600", null, true);
    }

    private Response playersResponse(Map<String, String> query, String ifNoneMatch) {
        if (players == null || !config.players().show()) {
            return Response.json(200, "{\"revision\":0,\"players\":[]}");
        }
        String world = query.get("world");
        if (world != null && !knownSlugs.get().contains(world)) {
            return Response.error(400, "unknown world");
        }
        long since = parseLong(query.get("since"), 0);
        String etag = "\"p" + players.current().revision() + "-" + (world == null ? "all" : world)
                + "-" + since + "\"";
        if (etag.equals(ifNoneMatch)) {
            return Response.notModified(etag, "no-cache");
        }
        JsonObject body = players.buildResponse(world, since);
        return new Response(200, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), "no-cache", etag, true);
    }

    private <T extends OverlayItem> Response layerResponse(String layerId, OverlayLayer<T> layer,
                                                           Map<String, String> query, String ifNoneMatch,
                                                           int maxItems) {
        if (layer == null) {
            return Response.json(200, "{\"revision\":0,\"items\":[]}");
        }
        String world = query.get("world");
        if (world == null || !knownSlugs.get().contains(world)) {
            return Response.error(400, "world parameter required");
        }
        int[] bbox = null;
        if (query.containsKey("bbox")) {
            bbox = parseBbox(query.get("bbox"));
            if (bbox == null) {
                return Response.error(400, "invalid bbox (expected x1,z1,x2,z2)");
            }
        }
        long revision = layer.revision();
        String etag = "\"" + layerId.charAt(0) + revision + "-" + world + "-"
                + (bbox == null ? "all" : bbox[0] + "." + bbox[1] + "." + bbox[2] + "." + bbox[3]) + "\"";
        if (etag.equals(ifNoneMatch)) {
            return Response.notModified(etag, "no-cache");
        }
        List<T> items = bbox == null
                ? layer.queryAll(world, maxItems + 1)
                : layer.queryBox(world, bbox[0], bbox[1], bbox[2], bbox[3], maxItems + 1);
        JsonObject root = new JsonObject();
        root.addProperty("revision", revision);
        root.addProperty("layer", layerId);
        JsonArray array = new JsonArray();
        int count = 0;
        for (T item : items) {
            if (count++ >= maxItems) {
                root.addProperty("truncated", true);
                break;
            }
            array.add(item.toJson());
        }
        root.add("items", array);
        return new Response(200, "application/json; charset=utf-8",
                root.toString().getBytes(StandardCharsets.UTF_8), "no-cache", etag, true);
    }

    static int[] parseBbox(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            int x1 = Integer.parseInt(parts[0].trim());
            int z1 = Integer.parseInt(parts[1].trim());
            int x2 = Integer.parseInt(parts[2].trim());
            int z2 = Integer.parseInt(parts[3].trim());
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);
            if ((long) maxX - minX > MAX_BBOX_SPAN || (long) maxZ - minZ > MAX_BBOX_SPAN) {
                return null;
            }
            return new int[]{minX, minZ, maxX, maxZ};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
