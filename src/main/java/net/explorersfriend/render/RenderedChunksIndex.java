package net.explorersfriend.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent per-dimension bitmap of which chunks have ever been rendered
 * ({@code 1024 bits ≙ 128 bytes per region}). Drives "render newly explored chunks"
 * (a loaded chunk whose bit is unset gets scheduled) and the {@code update} command.
 *
 * <p>Thread-safe: bit mutation happens under the per-region array lock; save snapshots
 * are atomic files. Losing this file is harmless — chunks simply re-render once.</p>
 */
public final class RenderedChunksIndex {

    public static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, long[]>> byDimension = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public RenderedChunksIndex(Path file) {
        this.file = file;
    }

    public static RenderedChunksIndex load(Path file) {
        RenderedChunksIndex index = new RenderedChunksIndex(file);
        if (!Files.exists(file)) {
            return index;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                return index;
            }
            for (Map.Entry<String, JsonElement> dimEntry : root.getAsJsonObject("dimensions").entrySet()) {
                ConcurrentHashMap<Long, long[]> regions = new ConcurrentHashMap<>();
                for (Map.Entry<String, JsonElement> regionEntry : dimEntry.getValue().getAsJsonObject().entrySet()) {
                    String[] parts = regionEntry.getKey().split(",");
                    long key = regionKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    byte[] bytes = Base64.getDecoder().decode(regionEntry.getValue().getAsString());
                    long[] bits = new long[16];
                    for (int i = 0; i < 16 && i * 8 + 7 < bytes.length; i++) {
                        for (int b = 0; b < 8; b++) {
                            bits[i] |= (bytes[i * 8 + b] & 0xFFL) << (b * 8);
                        }
                    }
                    regions.put(key, bits);
                }
                index.byDimension.put(dimEntry.getKey(), regions);
            }
        } catch (Exception e) {
            Path backup = MoreFiles.quarantine(file);
            ExplorersFriend.LOGGER.warn(
                    "[ExplorersFriend/Cache] Rendered-chunk index was corrupt ({}); moved to {}; chunks re-render once",
                    e.getMessage(), backup);
            index.byDimension.clear();
        }
        return index;
    }

    public boolean isRendered(String dimensionSlug, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, long[]> regions = byDimension.get(dimensionSlug);
        if (regions == null) {
            return false;
        }
        long[] bits = regions.get(regionKey(chunkX >> 5, chunkZ >> 5));
        if (bits == null) {
            return false;
        }
        int bit = bitIndex(chunkX, chunkZ);
        synchronized (bits) {
            return (bits[bit >> 6] & (1L << (bit & 63))) != 0;
        }
    }

    public void markRendered(String dimensionSlug, int chunkX, int chunkZ) {
        long[] bits = byDimension
                .computeIfAbsent(dimensionSlug, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey(chunkX >> 5, chunkZ >> 5), k -> new long[16]);
        int bit = bitIndex(chunkX, chunkZ);
        synchronized (bits) {
            bits[bit >> 6] |= 1L << (bit & 63);
        }
        dirty.set(true);
    }

    /** Clears one dimension (used by cache prune / forced full re-render). */
    public void clearDimension(String dimensionSlug) {
        byDimension.remove(dimensionSlug);
        dirty.set(true);
    }

    /** Persists when anything changed since the last save. Safe to call periodically. */
    public void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject dims = new JsonObject();
        for (Map.Entry<String, ConcurrentHashMap<Long, long[]>> dimEntry : byDimension.entrySet()) {
            JsonObject regions = new JsonObject();
            for (Map.Entry<Long, long[]> regionEntry : dimEntry.getValue().entrySet()) {
                long key = regionEntry.getKey();
                long[] bits = regionEntry.getValue();
                byte[] bytes = new byte[128];
                synchronized (bits) {
                    for (int i = 0; i < 16; i++) {
                        for (int b = 0; b < 8; b++) {
                            bytes[i * 8 + b] = (byte) (bits[i] >>> (b * 8));
                        }
                    }
                }
                regions.addProperty((int) (key >> 32) + "," + (int) key,
                        Base64.getEncoder().encodeToString(bytes));
            }
            dims.add(dimEntry.getKey(), regions);
        }
        root.add("dimensions", dims);
        try {
            MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
        } catch (IOException e) {
            dirty.set(true); // retry on the next periodic save
            ExplorersFriend.LOGGER.warn("[ExplorersFriend/Cache] Could not save rendered-chunk index: {}", e.toString());
        }
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int bitIndex(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
    }
}
