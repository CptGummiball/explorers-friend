package net.explorersfriend.marker;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * One persistent marker. Immutable; mutations go through {@link MarkerStore} which
 * produces updated copies. {@code source} is "command" or "banner"; banner markers use
 * the stable id {@code banner:<dimension-slug>:<x>:<y>:<z>} so re-placing at the same
 * position updates instead of duplicating, while equal names elsewhere stay separate.
 */
public record MapMarker(
        String id,
        String dimensionSlug,
        String name,
        String icon,
        int x,
        int y,
        int z,
        String description,
        String category,
        Integer colorRgb,
        UUID creator,
        String creatorName,
        long createdAtEpochMs,
        long updatedAtEpochMs,
        boolean visible,
        String source,
        String bannerDesign) {

    public static final String SOURCE_COMMAND = "command";
    public static final String SOURCE_BANNER = "banner";

    public static String bannerId(String dimensionSlug, int x, int y, int z) {
        return "banner:" + dimensionSlug + ":" + x + ":" + y + ":" + z;
    }

    public boolean isBanner() {
        return SOURCE_BANNER.equals(source);
    }

    /** @param showCreator/showCoordinates privacy switches from the config */
    public JsonObject toJson(boolean showCreator, boolean showCoordinates, String bannerIconHash) {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("name", name);
        out.addProperty("icon", icon);
        out.addProperty("source", source);
        if (showCoordinates) {
            out.addProperty("x", x);
            out.addProperty("y", y);
            out.addProperty("z", z);
        } else {
            // position is still needed to draw the marker; strip only the visible y
            out.addProperty("x", x);
            out.addProperty("z", z);
        }
        if (category != null) {
            out.addProperty("category", category);
        }
        if (description != null) {
            out.addProperty("description", description);
        }
        if (colorRgb != null) {
            out.addProperty("color", String.format("#%06x", colorRgb));
        }
        if (showCreator && creatorName != null) {
            out.addProperty("creator", creatorName);
        }
        if (bannerIconHash != null) {
            out.addProperty("bannerIcon", bannerIconHash);
        }
        return out;
    }
}
