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
            "shop", "spawn", "station", "trader", "treasure", "village", "waypoint", "waystone");

    /** Prefix for user-supplied icons: {@code custom:<name>} (see CustomIconStore). */
    public static final String CUSTOM_PREFIX = "custom:";

    private static volatile java.util.Set<String> customIds = java.util.Set.of();

    private IconLibrary() {
    }

    /** Publishes the currently loaded custom icon names (called by CustomIconStore). */
    public static void setCustomIcons(java.util.Set<String> names) {
        customIds = java.util.Set.copyOf(names);
    }

    public static java.util.Set<String> customIcons() {
        return customIds;
    }

    /** Built-in ids plus {@code custom:}-prefixed ids of currently loaded custom icons. */
    public static List<String> allIcons() {
        if (customIds.isEmpty()) {
            return ICONS;
        }
        java.util.List<String> all = new java.util.ArrayList<>(ICONS);
        customIds.stream().sorted().forEach(name -> all.add(CUSTOM_PREFIX + name));
        return List.copyOf(all);
    }

    public static boolean isKnown(String iconId) {
        if (iconId == null) {
            return false;
        }
        if (iconId.startsWith(CUSTOM_PREFIX)) {
            return customIds.contains(iconId.substring(CUSTOM_PREFIX.length()));
        }
        return ICONS.contains(iconId);
    }

    public static String validateOrFallback(String iconId) {
        return isKnown(iconId) ? iconId : FALLBACK;
    }

    /** Classpath resource path of an icon (only call with validated ids). */
    public static String resourcePath(String iconId) {
        return "web/icons/" + validateOrFallback(iconId) + ".svg";
    }
}
