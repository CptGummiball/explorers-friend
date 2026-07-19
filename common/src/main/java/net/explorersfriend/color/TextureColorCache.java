package net.explorersfriend.color;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-addressed texture color cache: {@code sha256(png bytes) → representative color}.
 * Identical textures — across any number of JARs and mod versions — are analyzed exactly
 * once, ever. Entries are only valid for one (algorithmVersion, animationMode) pair;
 * a mismatch discards the file wholesale, which silently triggers recomputation.
 *
 * <p>Thread-safe map; {@link #save} snapshots atomically.</p>
 */
public final class TextureColorCache {

    public static final int SCHEMA_VERSION = 1;

    private final ConcurrentHashMap<String, Integer> colors = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public static TextureColorCache load(Path file, int algorithmVersion, String animationMode) {
        TextureColorCache cache = new TextureColorCache();
        if (!Files.exists(file)) {
            return cache;
        }
        try {
            JsonObject root = JsonParser
                    .parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION
                    || root.get("algorithmVersion").getAsInt() != algorithmVersion
                    || !animationMode.equals(root.get("animationMode").getAsString())) {
                Log.LOGGER.info(
                        "[ExplorersFriend/Cache] Texture color cache uses an older schema/algorithm; recomputing");
                return cache;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("entries").entrySet()) {
                cache.colors.put(entry.getKey(), entry.getValue().getAsInt());
            }
        } catch (Exception e) {
            Path backup = MoreFiles.quarantine(file);
            Log.LOGGER.warn(
                    "[ExplorersFriend/Cache] Texture color cache was corrupt ({}); moved to {} and recomputing",
                    e.getMessage(), backup);
            cache.colors.clear();
        }
        return cache;
    }

    /** Cached color for a texture hash, or {@code null}. Counts hit/miss statistics. */
    public Integer get(String sha256) {
        Integer color = colors.get(sha256);
        if (color != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return color;
    }

    public void put(String sha256, int argb) {
        colors.put(sha256, argb);
    }

    public void save(Path file, int algorithmVersion, String animationMode) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("algorithmVersion", algorithmVersion);
        root.addProperty("animationMode", animationMode);
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            entries.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("entries", entries);
        MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
    }

    public int size() {
        return colors.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    /** Hit ratio in percent for the scan summary; 100 when nothing was requested. */
    public double hitRatioPercent() {
        long total = hits.get() + misses.get();
        return total == 0 ? 100.0 : 100.0 * hits.get() / total;
    }
}
