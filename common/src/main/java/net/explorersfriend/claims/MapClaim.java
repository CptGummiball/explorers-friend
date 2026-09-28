package net.explorersfriend.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.overlay.OverlayItem;

import java.util.List;
import java.util.HashSet;

/**
 * Provider-independent claim: one owner/team area in one dimension, geometrically a
 * list of merged block-coordinate rectangles and/or polygon outlines (supports
 * multi-part areas without exploding chunk claims into 16×16 squares).
 *
 * <p>Immutable and already privacy-filtered: name/owner/team are {@code null} when the
 * config hides them, and {@link #toJson()} sends exactly what the browser may see —
 * never technical IDs, member lists or permission data.</p>
 */
public record MapClaim(
        String id,
        String providerId,
        String dimensionSlug,
        List<ClaimRect> rects,
        List<List<ClaimPoint>> polygons,
        String claimName,
        String ownerName,
        String teamName,
        int fillArgb,
        int borderArgb,
        long updatedAtEpochMs) implements OverlayItem {

    /** Block-coordinate rectangle, inclusive. */
    public record ClaimRect(int minX, int minZ, int maxX, int maxZ) {

        public static ClaimRect ofChunk(int chunkX, int chunkZ) {
            return new ClaimRect(chunkX << 4, chunkZ << 4, (chunkX << 4) + 15, (chunkZ << 4) + 15);
        }
    }

    /** A vertex in block X/Z coordinates. Rings are closed implicitly. */
    public record ClaimPoint(int x, int z) {
    }

    /** Keeps rectangle-only providers source compatible. */
    public MapClaim(String id, String providerId, String dimensionSlug, List<ClaimRect> rects,
                    String claimName, String ownerName, String teamName, int fillArgb,
                    int borderArgb, long updatedAtEpochMs) {
        this(id, providerId, dimensionSlug, rects, List.of(), claimName, ownerName,
                teamName, fillArgb, borderArgb, updatedAtEpochMs);
    }

    public MapClaim {
        rects = List.copyOf(rects);
        polygons = polygons.stream().map(MapClaim::validateRing).toList();
        if (rects.isEmpty() && polygons.isEmpty()) {
            throw new IllegalArgumentException("claim without geometry");
        }
    }

    static List<ClaimPoint> validateRing(List<ClaimPoint> input) {
        List<ClaimPoint> ring = List.copyOf(input);
        if (ring.size() > 3 && ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring = List.copyOf(ring.subList(0, ring.size() - 1));
        }
        if (ring.size() < 3 || new HashSet<>(ring).size() != ring.size()) {
            throw new IllegalArgumentException("polygon needs at least three distinct vertices");
        }
        double twiceArea = 0;
        for (int i = 0; i < ring.size(); i++) {
            ClaimPoint a = ring.get(i), b = ring.get((i + 1) % ring.size());
            twiceArea += (double) a.x() * b.z() - (double) b.x() * a.z();
        }
        if (twiceArea == 0) {
            throw new IllegalArgumentException("polygon has zero area");
        }
        return ring;
    }

    @Override
    public int minX() {
        int min = Integer.MAX_VALUE;
        for (ClaimRect rect : rects) {
            min = Math.min(min, rect.minX());
        }
        for (List<ClaimPoint> ring : polygons) {
            for (ClaimPoint point : ring) min = Math.min(min, point.x());
        }
        return min;
    }

    @Override
    public int minZ() {
        int min = Integer.MAX_VALUE;
        for (ClaimRect rect : rects) {
            min = Math.min(min, rect.minZ());
        }
        for (List<ClaimPoint> ring : polygons) {
            for (ClaimPoint point : ring) min = Math.min(min, point.z());
        }
        return min;
    }

    @Override
    public int maxX() {
        int max = Integer.MIN_VALUE;
        for (ClaimRect rect : rects) {
            max = Math.max(max, rect.maxX());
        }
        for (List<ClaimPoint> ring : polygons) {
            for (ClaimPoint point : ring) max = Math.max(max, point.x());
        }
        return max;
    }

    @Override
    public int maxZ() {
        int max = Integer.MIN_VALUE;
        for (ClaimRect rect : rects) {
            max = Math.max(max, rect.maxZ());
        }
        for (List<ClaimPoint> ring : polygons) {
            for (ClaimPoint point : ring) max = Math.max(max, point.z());
        }
        return max;
    }

    @Override
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("provider", providerId);
        JsonArray rectArray = new JsonArray();
        for (ClaimRect rect : rects) {
            JsonArray coordinates = new JsonArray();
            coordinates.add(rect.minX());
            coordinates.add(rect.minZ());
            coordinates.add(rect.maxX());
            coordinates.add(rect.maxZ());
            rectArray.add(coordinates);
        }
        out.add("rects", rectArray);
        if (!polygons.isEmpty()) {
            JsonArray polygonArray = new JsonArray();
            for (List<ClaimPoint> ring : polygons) {
                JsonArray vertices = new JsonArray();
                for (ClaimPoint point : ring) {
                    JsonArray pair = new JsonArray();
                    pair.add(point.x());
                    pair.add(point.z());
                    vertices.add(pair);
                }
                polygonArray.add(vertices);
            }
            out.add("polygons", polygonArray);
        }
        if (claimName != null && !claimName.isBlank()) {
            out.addProperty("name", claimName);
        }
        if (ownerName != null && !ownerName.isBlank()) {
            out.addProperty("owner", ownerName);
        }
        if (teamName != null && !teamName.isBlank()) {
            out.addProperty("team", teamName);
        }
        out.addProperty("fill", String.format("#%08x", fillArgb));
        out.addProperty("border", String.format("#%06x", borderArgb & 0xFFFFFF));
        return out;
    }
}
