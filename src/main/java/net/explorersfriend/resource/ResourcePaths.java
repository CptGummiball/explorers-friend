package net.explorersfriend.resource;

import java.util.Locale;

/**
 * Helpers for Minecraft resource identifiers ("namespace:path") and their asset paths.
 * Pure string logic, no Minecraft classes — unit-testable.
 */
public final class ResourcePaths {

    private ResourcePaths() {
    }

    /** Adds the "minecraft" namespace when missing; lower-cases defensively. */
    public static String normalizeId(String id) {
        String value = id.strip().toLowerCase(Locale.ROOT);
        return value.indexOf(':') >= 0 ? value : "minecraft:" + value;
    }

    public static String namespaceOf(String id) {
        String normalized = normalizeId(id);
        return normalized.substring(0, normalized.indexOf(':'));
    }

    public static String pathOf(String id) {
        String normalized = normalizeId(id);
        return normalized.substring(normalized.indexOf(':') + 1);
    }

    /** Is this a syntactically valid, filesystem-safe identifier? */
    public static boolean isValidId(String id) {
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        String ns = colon >= 0 ? normalized.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? normalized.substring(colon + 1) : normalized;
        return ns.matches("[a-z0-9_.-]+") && path.matches("[a-z0-9_.\\-/]+") && !path.contains("..");
    }

    public static String blockstatePath(String blockId) {
        return "assets/" + namespaceOf(blockId) + "/blockstates/" + pathOf(blockId) + ".json";
    }

    public static String modelPath(String modelId) {
        return "assets/" + namespaceOf(modelId) + "/models/" + pathOf(modelId) + ".json";
    }

    public static String texturePath(String textureId) {
        return "assets/" + namespaceOf(textureId) + "/textures/" + pathOf(textureId) + ".png";
    }

    public static String textureMetaPath(String textureId) {
        return texturePath(textureId) + ".mcmeta";
    }
}
