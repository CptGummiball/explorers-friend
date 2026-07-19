package net.explorersfriend.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.Log;
import net.explorersfriend.util.MoreFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent per-dimension set of chunks that a point-query protection API (the
 * Common Protection API) reported as protected. The API cannot enumerate claim
 * areas, so the map grows its picture from chunks the server actually loads —
 * documented behavior, not a bug. Chunks that later report unprotected heal out.
 *
 * <p>Thread model: {@link #update} is called from the server thread only;
 * {@link #toRawAreas} may be called from web/worker threads (concurrent map +
 * copied sets). Saving is atomic and debounced by the caller via {@link #saveIfDirty}.</p>
 */
public final class ProtectedChunkCache {

    private final Path file;
    private final Map<String, Set<Long>> chunksByDimension = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public ProtectedChunkCache(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = Jsonc.parse(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Set<Long> chunks = ConcurrentHashMap.newKeySet();
                for (JsonElement e : entry.getValue().getAsJsonArray()) {
                    chunks.add(e.getAsLong());
                }
                chunksByDimension.put(entry.getKey(), chunks);
            }
        } catch (IOException | RuntimeException e) {
            Log.LOGGER.warn("[ExplorersFriend/Claims] Ignoring unreadable protected-chunk cache {}: {}",
                    file.getFileName(), e.toString());
        }
    }

    /** Returns true when the stored state changed (caller triggers a layer refresh). */
    public boolean update(String dimensionId, long packedChunk, boolean isProtected) {
        Set<Long> chunks = chunksByDimension.computeIfAbsent(dimensionId,
                k -> ConcurrentHashMap.newKeySet());
        boolean changed = isProtected ? chunks.add(packedChunk) : chunks.remove(packedChunk);
        if (changed) {
            dirty = true;
        }
        return changed;
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Set<Long>> entry : chunksByDimension.entrySet()) {
            JsonArray array = new JsonArray();
            entry.getValue().stream().sorted().forEach(array::add);
            root.add(entry.getKey(), array);
        }
        try {
            MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
        } catch (IOException e) {
            dirty = true;
            Log.LOGGER.warn("[ExplorersFriend/Claims] Could not save protected-chunk cache: {}",
                    e.toString());
        }
    }

    /** One area per dimension, merged into rectangles by the usual pipeline. */
    public List<ClaimProvider.RawArea> toRawAreas() {
        List<ClaimProvider.RawArea> out = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : chunksByDimension.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            out.add(ClaimProvider.RawArea.ofChunks(
                    "cpa|" + entry.getKey(),
                    entry.getKey(),
                    Set.copyOf(entry.getValue()),
                    null,
                    "Protected area",
                    null,
                    null,
                    false));
        }
        return out;
    }
}
