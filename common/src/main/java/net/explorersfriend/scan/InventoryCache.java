package net.explorersfriend.scan;

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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistence for the JAR inventory ({@code cache/jar-inventory.json}).
 *
 * <p>Writes are atomic. A corrupt or schema-incompatible file is quarantined
 * ({@code *.corrupt-N}) and treated as absent, which simply causes a full re-scan —
 * never a crash. Not thread-safe; only the startup pipeline touches it.</p>
 */
public final class InventoryCache {

    public static final int SCHEMA_VERSION = 1;

    private InventoryCache() {
    }

    /** @return cached records keyed by location, or an empty map when absent/corrupt. */
    public static Map<String, JarRecord> load(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
            if (schema != SCHEMA_VERSION) {
                Log.LOGGER.info(
                        "[ExplorersFriend/Scanner] Inventory cache has schema {} (expected {}); rebuilding",
                        schema, SCHEMA_VERSION);
                return Map.of();
            }
            Map<String, JarRecord> out = new LinkedHashMap<>();
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                JarRecord record = new JarRecord(
                        o.get("locationKey").getAsString(),
                        o.get("modId").getAsString(),
                        o.get("version").getAsString(),
                        o.get("fileName").getAsString(),
                        o.get("nestedIn").getAsString(),
                        o.get("size").getAsLong(),
                        o.get("mtime").getAsLong(),
                        o.get("sha256").getAsString());
                out.put(record.locationKey(), record);
            }
            return out;
        } catch (Exception e) {
            Path backup = MoreFiles.quarantine(file);
            Log.LOGGER.warn(
                    "[ExplorersFriend/Scanner] Inventory cache {} was corrupt ({}); moved to {} and rebuilding",
                    file.getFileName(), e.getMessage(), backup);
            return Map.of();
        }
    }

    public static void save(Path file, Collection<JarRecord> records) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray entries = new JsonArray();
        for (JarRecord record : records) {
            JsonObject o = new JsonObject();
            o.addProperty("locationKey", record.locationKey());
            o.addProperty("modId", record.modId());
            o.addProperty("version", record.version());
            o.addProperty("fileName", record.fileName());
            o.addProperty("nestedIn", record.nestedIn());
            o.addProperty("size", record.size());
            o.addProperty("mtime", record.mtime());
            o.addProperty("sha256", record.sha256());
            entries.add(o);
        }
        root.add("entries", entries);
        MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
    }
}
