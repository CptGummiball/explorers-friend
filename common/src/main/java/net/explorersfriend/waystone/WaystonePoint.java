package net.explorersfriend.waystone;

import com.google.gson.JsonObject;
import net.explorersfriend.overlay.OverlayItem;

/**
 * One waystone shown on the map, produced by the per-platform Waystones adapter and
 * already privacy-filtered (visibility rules and the show-owner switch are applied
 * before construction). The JSON mirrors the marker shape, so the web UI renders
 * waystones through the same pipeline (clustering, tooltips) as markers.
 */
public record WaystonePoint(
        String id,
        String dimensionSlug,
        int x,
        int y,
        int z,
        String name,
        String ownerName,     // null = hidden or unowned
        boolean global) implements OverlayItem {

    @Override
    public int minX() {
        return x;
    }

    @Override
    public int minZ() {
        return z;
    }

    @Override
    public int maxX() {
        return x;
    }

    @Override
    public int maxZ() {
        return z;
    }

    @Override
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("name", name);
        out.addProperty("icon", "waystone");
        out.addProperty("source", "waystone");
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("category", global ? "Global waystone" : "Waystone");
        if (ownerName != null) {
            out.addProperty("creator", ownerName);
        }
        return out;
    }
}
