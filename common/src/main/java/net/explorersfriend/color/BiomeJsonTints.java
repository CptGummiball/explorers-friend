package net.explorersfriend.color;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.render.RuntimePalette;
import net.explorersfriend.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Biome grass/foliage/water tints computed from the vanilla worldgen JSONs that
 * ship inside the game jar ({@code data/minecraft/worldgen/biome/*.json}, present
 * since 1.19+). Behaviour-equivalent to the registry-driven BiomeTintTable used on
 * the mod loaders: climate (temperature/downfall) samples the vanilla colormaps,
 * explicit effect colors override, and the swamp/dark-forest grass modifiers are
 * applied like the game does. Used by backends without registry access (Spigot).
 *
 * <p>Returns an empty map when the jar carries no biome JSONs - callers fall back
 * to default tints and log the limitation instead of failing.</p>
 */
public final class BiomeJsonTints {

    private static final String PREFIX = "data/minecraft/worldgen/biome/";

    private BiomeJsonTints() {
    }

    /** @return biome id -> {grassRgb, foliageRgb, waterRgb}; empty when unavailable. */
    public static Map<String, int[]> fromJar(Path gameJar,
                                             ColormapSampler grassMap,
                                             ColormapSampler foliageMap) {
        Map<String, int[]> byName = new HashMap<>();
        if (gameJar == null) {
            return byName;
        }
        try (ZipFile zip = new ZipFile(gameJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(PREFIX) || !name.endsWith(".json") || entry.isDirectory()) {
                    continue;
                }
                String biomeId = "minecraft:" + name.substring(PREFIX.length(), name.length() - 5);
                try (InputStreamReader reader = new InputStreamReader(
                        zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    byName.put(biomeId, tintsOf(root, grassMap, foliageMap));
                } catch (RuntimeException e) {
                    Log.LOGGER.debug("[ExplorersFriend/Colors] Skipping biome json {}: {}",
                            name, e.toString());
                }
            }
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Colors] Cannot read biome data from {}: {}",
                    gameJar.getFileName(), e.toString());
        }
        if (!byName.isEmpty()) {
            Log.LOGGER.info("[ExplorersFriend/Colors] Computed biome tints for {} biome(s) "
                    + "from worldgen data", byName.size());
        }
        return byName;
    }

    private static int[] tintsOf(JsonObject biome, ColormapSampler grassMap, ColormapSampler foliageMap) {
        float temperature = floatOf(biome, "temperature", 0.5f);
        float downfall = floatOf(biome, "downfall", 0.5f);
        JsonObject effects = biome.has("effects") && biome.get("effects").isJsonObject()
                ? biome.getAsJsonObject("effects") : new JsonObject();

        int grass = intOf(effects, "grass_color", grassMap.sample(temperature, downfall));
        String modifier = effects.has("grass_color_modifier")
                ? effects.get("grass_color_modifier").getAsString().toLowerCase(Locale.ROOT) : "none";
        grass = switch (modifier) {
            case "dark_forest" -> ((grass & 0xFEFEFE) + 0x28340A) >> 1;
            case "swamp" -> 0x6A7039;
            default -> grass;
        };
        int foliage = intOf(effects, "foliage_color", foliageMap.sample(temperature, downfall));
        int water = intOf(effects, "water_color", RuntimePalette.DEFAULT_WATER_RGB);
        return new int[]{grass, foliage, water};
    }

    private static float floatOf(JsonObject obj, String key, float fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsFloat() : fallback;
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsInt() & 0xFFFFFF : fallback;
    }
}
