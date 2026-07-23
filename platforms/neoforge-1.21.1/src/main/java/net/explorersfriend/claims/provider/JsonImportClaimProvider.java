package net.explorersfriend.claims.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.claims.ChunkRectMerger;
import net.explorersfriend.claims.ClaimProvider;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.config.ConfigIO;
import net.explorersfriend.util.Jsonc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * File-based claim source: {@code config/explorersfriend/claims-import.jsonc}. Serves
 * as the bridge for systems without a Fabric API (e.g. a GriefPrevention export from a
 * Paper server) and for hand-maintained protected zones. Reloaded when the file's
 * mtime changes; parse errors keep the previous data.
 *
 * <p>Format (JSON with comments):</p>
 * <pre>
 * [
 *   { "id": "spawn", "world": "minecraft:overworld", "name": "Spawn",
 *     "owner": "Admins", "team": null, "color": "#ff8800",
 *     "rects": [[-128, -128, 127, 127]],          // block coords, inclusive
 *     "chunks": [[12, -3], [12, -2]] }             // and/or chunk coords
 * ]
 * </pre>
 */
public final class JsonImportClaimProvider implements ClaimProvider {

    private final Path file;

    public JsonImportClaimProvider(Path file) {
        this.file = file;
    }

    @Override
    public String providerId() {
        return "jsonimport";
    }

    @Override
    public String displayName() {
        return "JSON import";
    }

    @Override
    public boolean isAvailable() {
        return Files.isRegularFile(file);
    }

    @Override
    public List<RawArea> copyRawClaims() {
        List<RawArea> out = new ArrayList<>();
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("claims-import unreadable: " + e.getMessage(), e);
        }
        JsonElement parsed = Jsonc.parse(raw);
        if (!parsed.isJsonArray()) {
            throw new IllegalStateException("claims-import.jsonc must contain a JSON array");
        }
        int index = 0;
        for (JsonElement element : parsed.getAsJsonArray()) {
            index++;
            try {
                JsonObject obj = element.getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : "entry-" + index;
                String world = obj.get("world").getAsString();
                Set<Long> chunks = new HashSet<>();
                if (obj.has("chunks") && obj.get("chunks").isJsonArray()) {
                    for (JsonElement chunk : obj.getAsJsonArray("chunks")) {
                        JsonArray pair = chunk.getAsJsonArray();
                        chunks.add(ChunkRectMerger.pack(pair.get(0).getAsInt(), pair.get(1).getAsInt()));
                    }
                }
                List<MapClaim.ClaimRect> rects = new ArrayList<>();
                if (obj.has("rects") && obj.get("rects").isJsonArray()) {
                    for (JsonElement rect : obj.getAsJsonArray("rects")) {
                        JsonArray box = rect.getAsJsonArray();
                        int minX = Math.min(box.get(0).getAsInt(), box.get(2).getAsInt());
                        int minZ = Math.min(box.get(1).getAsInt(), box.get(3).getAsInt());
                        int maxX = Math.max(box.get(0).getAsInt(), box.get(2).getAsInt());
                        int maxZ = Math.max(box.get(1).getAsInt(), box.get(3).getAsInt());
                        rects.add(new MapClaim.ClaimRect(minX, minZ, maxX, maxZ));
                    }
                }
                if (chunks.isEmpty() && rects.isEmpty()) {
                    continue;
                }
                if (!chunks.isEmpty()) {
                    rects.addAll(ChunkRectMerger.merge(chunks));
                }
                Integer color = obj.has("color") ? ConfigIO.parseColor(obj.get("color").getAsString()) : null;
                out.add(new RawArea("import:" + id, world, Set.of(), rects,
                        asStringOrNull(obj, "name"), asStringOrNull(obj, "owner"),
                        asStringOrNull(obj, "team"),
                        color == null ? null : color & 0xFFFFFF, false));
            } catch (Exception e) {
                ExplorersFriend.LOGGER.warn("[ExplorersFriend/Claims] claims-import entry {} invalid: {}",
                        index, e.getMessage());
            }
        }
        return out;
    }

    private static String asStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }
}
