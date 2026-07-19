package net.explorersfriend.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.overlay.OverlayItem;

import java.util.List;

/**
 * Provider-independent claim: one owner/team area in one dimension, geometrically a
 * list of merged block-coordinate rectangles (supports multi-part areas, enclaves and
 * chunk-based systems without exploding into thousands of 16×16 squares).
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

    public MapClaim {
        rects = List.copyOf(rects);
        if (rects.isEmpty()) {
            throw new IllegalArgumentException("claim without geometry");
        }
    }

    @Override
    public int minX() {
        int min = Integer.MAX_VALUE;
        for (ClaimRect rect : rects) {
            min = Math.min(min, rect.minX());
        }
        return min;
    }

    @Override
    public int minZ() {
        int min = Integer.MAX_VALUE;
        for (ClaimRect rect : rects) {
            min = Math.min(min, rect.minZ());
        }
        return min;
    }

    @Override
    public int maxX() {
        int max = Integer.MIN_VALUE;
        for (ClaimRect rect : rects) {
            max = Math.max(max, rect.maxX());
        }
        return max;
    }

    @Override
    public int maxZ() {
        int max = Integer.MIN_VALUE;
        for (ClaimRect rect : rects) {
            max = Math.max(max, rect.maxZ());
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
