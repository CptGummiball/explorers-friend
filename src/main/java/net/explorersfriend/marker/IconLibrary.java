package net.explorersfriend.marker;

import java.util.List;

/**
 * The built-in marker icon set. Every icon is an original, self-contained SVG under
 * {@code web/icons/<id>.svg} (no external references, no scripts — safe to serve).
 * Icon ids are validated against this fixed list; unknown ids fall back to
 * {@link #FALLBACK}. External icon directories are deliberately not supported:
 * serving user-supplied SVG is an XSS vector (documented in SECURITY_PRIVACY.md).
 */
public final class IconLibrary {

    public static final String FALLBACK = "waypoint";

    /** Stable, sorted list of available icon ids. */
    public static final List<String> ICONS = List.of(
            "banner", "bed", "castle", "cave", "city", "custom", "danger", "end_portal",
            "event", "farm", "harbor", "house", "info", "mine", "nether_portal", "portal",
            "shop", "spawn", "station", "trader", "treasure", "village", "waypoint");

    private IconLibrary() {
    }

    public static boolean isKnown(String iconId) {
        return iconId != null && ICONS.contains(iconId);
    }

    public static String validateOrFallback(String iconId) {
        return isKnown(iconId) ? iconId : FALLBACK;
    }

    /** Classpath resource path of an icon (only call with validated ids). */
    public static String resourcePath(String iconId) {
        return "web/icons/" + validateOrFallback(iconId) + ".svg";
    }
}
