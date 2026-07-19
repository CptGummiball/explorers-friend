package net.explorersfriend.color;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.util.Log;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the final block → color mapping ({@code cache/block-colors.json}).
 * The whole file is only valid for one exact combination of (jarSetHash,
 * algorithmVersion, animationMode); any deviation — a mod added, removed, up- or
 * downgraded, or a new sampling algorithm — invalidates it and forces a re-scan
 * (which reuses the content-addressed texture cache, so re-scans stay fast).
 */
public final class BlockColorCache {

    public static final int SCHEMA_VERSION = 1;

    private BlockColorCache() {
    }

    /** @return the cached mapping when fully valid for the given context, else empty. */
    public static Optional<Map<String, BlockColorResult>> loadIfValid(
            Path file, String jarSetHash, int algorithmVersion, String animationMode) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser
                    .parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                Log.LOGGER.info("[ExplorersFriend/Cache] Block color cache schema outdated; re-scanning");
                return Optional.empty();
            }
            if (root.get("algorithmVersion").getAsInt() != algorithmVersion
                    || !animationMode.equals(root.get("animationMode").getAsString())) {
                Log.LOGGER.info(
                        "[ExplorersFriend/Cache] Color algorithm changed; recomputing block colors");
                return Optional.empty();
            }
            if (!jarSetHash.equals(root.get("jarSetHash").getAsString())) {
                return Optional.empty(); // mods changed; caller logs the diff summary
            }
            Map<String, BlockColorResult> out = new LinkedHashMap<>();
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                BlockColorResult result = new BlockColorResult(
                        o.get("id").getAsString(),
                        o.get("argb").getAsInt(),
                        TintType.fromName(o.get("tint").getAsString()),
                        asStringOrNull(o.get("source")),
                        asStringOrNull(o.get("model")),
                        asStringOrNull(o.get("texture")),
                        asStringOrNull(o.get("reason")),
                        algorithmVersion,
                        o.get("time").getAsLong());
                out.put(result.blockId(), result);
            }
            return Optional.of(out);
        } catch (Exception e) {
            Path backup = MoreFiles.quarantine(file);
            Log.LOGGER.warn(
                    "[ExplorersFriend/Cache] Block color cache was corrupt ({}); moved to {} and re-scanning",
                    e.getMessage(), backup);
            return Optional.empty();
        }
    }

    public static void save(Path file, String jarSetHash, int algorithmVersion, String animationMode,
                            Map<String, BlockColorResult> colors) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("algorithmVersion", algorithmVersion);
        root.addProperty("animationMode", animationMode);
        root.addProperty("jarSetHash", jarSetHash);
        JsonArray entries = new JsonArray();
        for (BlockColorResult result : colors.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", result.blockId());
            o.addProperty("argb", result.argb());
            o.addProperty("tint", result.tint().name());
            o.addProperty("source", result.sourceId());
            o.addProperty("model", result.modelId());
            o.addProperty("texture", result.textureId());
            o.addProperty("reason", result.fallbackReason());
            o.addProperty("time", result.resolvedAtEpochMs());
            entries.add(o);
        }
        root.add("entries", entries);
        MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
    }

    private static String asStringOrNull(JsonElement el) {
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }
}
