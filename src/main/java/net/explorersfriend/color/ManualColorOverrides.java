package net.explorersfriend.color;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.config.ConfigIO;
import net.explorersfriend.resource.ResourcePaths;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-defined block colors from {@code config/explorersfriend/block-colors.jsonc}.
 * These always win over every automatically determined color. Reloadable at runtime
 * via {@code /efmap colors reload}.
 *
 * <p>File format (JSON with comments):
 * {@code { "minecraft:stone": "#7a7a7a", "somemod:weird_block": { "color": "#aarrggbb", "tint": "grass" } }}</p>
 */
public final class ManualColorOverrides {

    /** One override: the color, and optionally a tint category. */
    public record ColorOverride(int argb, TintType tint) {
    }

    private ManualColorOverrides() {
    }

    public static Map<String, ColorOverride> loadOrCreate(Path file) {
        if (!Files.exists(file)) {
            try {
                MoreFiles.writeAtomicUtf8(file, defaultTemplate());
            } catch (IOException e) {
                ExplorersFriend.LOGGER.warn("[ExplorersFriend/Colors] Could not write {}: {}", file, e.toString());
            }
            return Map.of();
        }
        Map<String, ColorOverride> out = new LinkedHashMap<>();
        try {
            JsonElement parsed = Jsonc.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                ExplorersFriend.LOGGER.warn("[ExplorersFriend/Colors] {} must contain a JSON object; ignoring", file);
                return Map.of();
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                String rawId = entry.getKey();
                if (!ResourcePaths.isValidId(rawId)) {
                    ExplorersFriend.LOGGER.warn("[ExplorersFriend/Colors] Invalid block id '{}' in {}; skipped",
                            rawId, file.getFileName());
                    continue;
                }
                String blockId = ResourcePaths.normalizeId(rawId);
                ColorOverride override = parseOverride(entry.getValue());
                if (override == null) {
                    ExplorersFriend.LOGGER.warn(
                            "[ExplorersFriend/Colors] Invalid color for '{}' in {}; expected \"#rrggbb\" or object",
                            rawId, file.getFileName());
                    continue;
                }
                out.put(blockId, override);
            }
        } catch (Exception e) {
            ExplorersFriend.LOGGER.warn("[ExplorersFriend/Colors] Could not parse {} ({}); manual colors ignored",
                    file, e.getMessage());
            return Map.of();
        }
        if (!out.isEmpty()) {
            ExplorersFriend.LOGGER.info("[ExplorersFriend/Colors] Loaded {} manual color override(s)", out.size());
        }
        return out;
    }

    private static ColorOverride parseOverride(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            Integer color = ConfigIO.parseColor(value.getAsString());
            return color == null ? null : new ColorOverride(color, TintType.NONE);
        }
        if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            JsonElement colorEl = obj.get("color");
            if (colorEl == null || !colorEl.isJsonPrimitive()) {
                return null;
            }
            Integer color = ConfigIO.parseColor(colorEl.getAsString());
            if (color == null) {
                return null;
            }
            TintType tint = obj.has("tint") ? TintType.fromName(obj.get("tint").getAsString()) : TintType.NONE;
            return new ColorOverride(color, tint);
        }
        return null;
    }

    private static String defaultTemplate() {
        return """
                // The Explorer's Friend — manual block colors.
                // Entries here always override automatically determined colors.
                // Reload at runtime with: /efmap colors reload
                //
                // Simple form:            "minecraft:stone": "#7a7a7a"
                // With alpha:             "minecraft:glass": "#40ffffff"
                // With biome tint:        "somemod:magic_grass": { "color": "#8ab84f", "tint": "grass" }
                //   valid tints: none, grass, foliage, water
                {
                }
                """;
    }
}
