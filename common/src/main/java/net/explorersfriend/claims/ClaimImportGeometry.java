package net.explorersfriend.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Shared JSONC import geometry parser for every supported server platform. */
public final class ClaimImportGeometry {
    private ClaimImportGeometry() {
    }

    /** Accepts one {@code points} ring and/or multiple {@code polygons} rings. */
    public static List<List<MapClaim.ClaimPoint>> parsePolygons(JsonObject obj) {
        List<List<MapClaim.ClaimPoint>> result = new ArrayList<>();
        if (obj.has("points") && !obj.get("points").isJsonNull()) {
            result.add(parseRing(obj.get("points")));
        }
        if (obj.has("polygons") && !obj.get("polygons").isJsonNull()) {
            for (JsonElement ring : obj.getAsJsonArray("polygons")) {
                result.add(parseRing(ring));
            }
        }
        return result;
    }

    private static List<MapClaim.ClaimPoint> parseRing(JsonElement element) {
        JsonArray vertices = element.getAsJsonArray();
        List<MapClaim.ClaimPoint> ring = new ArrayList<>(vertices.size());
        for (JsonElement vertex : vertices) {
            if (vertex.isJsonObject()) {
                JsonObject point = vertex.getAsJsonObject();
                ring.add(new MapClaim.ClaimPoint(point.get("x").getAsInt(), point.get("z").getAsInt()));
            } else {
                JsonArray pair = vertex.getAsJsonArray();
                if (pair.size() != 2) {
                    throw new IllegalArgumentException("polygon vertex must contain x and z");
                }
                ring.add(new MapClaim.ClaimPoint(pair.get(0).getAsInt(), pair.get(1).getAsInt()));
            }
        }
        return MapClaim.validateRing(ring);
    }
}
