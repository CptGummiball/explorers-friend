package net.explorersfriend.marker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.util.Log;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.overlay.OverlayItem;
import net.explorersfriend.overlay.OverlayLayer;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Persistent marker storage ({@code <data-dir>/markers.json}, schema-versioned) with a
 * rolling {@code .bak}, atomic writes, batched saves (dirty flag + interval + shutdown
 * flush) and hard validation of every value. Publishes to the {@code markers} overlay
 * layer after every mutation.
 *
 * <p>Thread-safety: all mutations are synchronized; reads for the web go through the
 * overlay layer's immutable snapshots.</p>
 */
public final class MarkerStore {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_DESCRIPTION_LENGTH = 256;
    public static final int MAX_CATEGORY_LENGTH = 24;

    private static final Logger LOGGER = Log.LOGGER;

    /** Overlay wrapper baking in the privacy config at publish time. */
    public record Item(MapMarker marker, boolean showCreator, boolean showCoordinates,
                       String bannerIconHash) implements OverlayItem {

        @Override
        public String id() {
            return marker.id();
        }

        @Override
        public String dimensionSlug() {
            return marker.dimensionSlug();
        }

        @Override
        public int minX() {
            return marker.x();
        }

        @Override
        public int minZ() {
            return marker.z();
        }

        @Override
        public int maxX() {
            return marker.x();
        }

        @Override
        public int maxZ() {
            return marker.z();
        }

        @Override
        public JsonObject toJson() {
            return marker.toJson(showCreator, showCoordinates, bannerIconHash);
        }
    }

    private final Path file;
    private final OverlayLayer<Item> layer;
    private final MapConfig.Markers config;
    private final Map<String, MapMarker> markers = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile Function<String, String> bannerIconHashResolver = design -> null;

    public MarkerStore(Path file, OverlayLayer<Item> layer, MapConfig.Markers config) {
        this.file = file;
        this.layer = layer;
        this.config = config;
    }

    public void setBannerIconHashResolver(Function<String, String> resolver) {
        this.bannerIconHashResolver = resolver;
    }

    // --- persistence -------------------------------------------------------

    public synchronized void load() {
        JsonObject root = readJson(file);
        if (root == null && Files.exists(backupPath())) {
            LOGGER.warn("[ExplorersFriend/Markers] markers.json unreadable; falling back to backup");
            root = readJson(backupPath());
        }
        markers.clear();
        int skipped = 0;
        if (root != null) {
            try {
                for (JsonElement el : root.getAsJsonArray("markers")) {
                    MapMarker marker = fromJson(el.getAsJsonObject());
                    if (marker != null) {
                        markers.put(marker.id(), marker);
                    } else {
                        skipped++;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Markers] Marker file structurally broken ({}); starting empty",
                        e.getMessage());
            }
        }
        long banners = markers.values().stream().filter(MapMarker::isBanner).count();
        LOGGER.info("[ExplorersFriend/Markers] Loaded {} persistent marker(s){}{}",
                markers.size(),
                banners > 0 ? " (" + banners + " banner)" : "",
                skipped > 0 ? ", " + skipped + " invalid entries skipped" : "");
        publish();
    }

    private JsonObject readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonObject root = JsonParser
                    .parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                LOGGER.warn("[ExplorersFriend/Markers] Unsupported marker schema in {}; ignoring",
                        path.getFileName());
                return null;
            }
            return root;
        } catch (Exception e) {
            MoreFiles.quarantine(path);
            LOGGER.warn("[ExplorersFriend/Markers] {} was corrupt ({}); quarantined",
                    path.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Batched save: only writes when dirty. Call from the scheduler + shutdown. */
    public synchronized void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray array = new JsonArray();
        for (MapMarker marker : markers.values()) {
            array.add(toJson(marker));
        }
        root.add("markers", array);
        try {
            if (Files.exists(file)) {
                Files.copy(file, backupPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            MoreFiles.writeAtomicUtf8(file, Jsonc.GSON.toJson(root));
        } catch (IOException e) {
            dirty.set(true);
            LOGGER.warn("[ExplorersFriend/Markers] Could not save markers: {}", e.toString());
        }
    }

    private Path backupPath() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    // --- mutations ---------------------------------------------------------

    /** @return error message or null on success */
    public synchronized String add(MapMarker marker) {
        if (markers.containsKey(marker.id())) {
            return "A marker with this id already exists.";
        }
        if (markers.size() >= config.maxTotal()) {
            return "The server-wide marker limit (" + config.maxTotal() + ") is reached.";
        }
        if (MapMarker.SOURCE_COMMAND.equals(marker.source()) && marker.creator() != null) {
            long own = markers.values().stream()
                    .filter(m -> marker.creator().equals(m.creator())
                            && MapMarker.SOURCE_COMMAND.equals(m.source()))
                    .count();
            if (own >= config.maxPerPlayer()) {
                return "You reached your marker limit (" + config.maxPerPlayer() + ").";
            }
        }
        String validation = validate(marker);
        if (validation != null) {
            return validation;
        }
        markers.put(marker.id(), marker);
        markDirtyAndPublish();
        return null;
    }

    /** Upserts a banner marker (no per-player limits; bounded by max-total). */
    public synchronized boolean upsertBanner(MapMarker marker) {
        if (!markers.containsKey(marker.id()) && markers.size() >= config.maxTotal()) {
            LOGGER.warn("[ExplorersFriend/Banners] Marker limit reached; banner at {}/{}/{} not registered",
                    marker.x(), marker.y(), marker.z());
            return false;
        }
        if (validate(marker) != null) {
            return false;
        }
        MapMarker previous = markers.put(marker.id(), marker);
        if (previous == null || !previous.equals(marker)) {
            markDirtyAndPublish();
            return true;
        }
        return false;
    }

    public synchronized boolean remove(String id) {
        if (markers.remove(id) != null) {
            markDirtyAndPublish();
            return true;
        }
        return false;
    }

    /** Applies an update function to one marker. @return error or null */
    public synchronized String update(String id, Function<MapMarker, MapMarker> change) {
        MapMarker existing = markers.get(id);
        if (existing == null) {
            return "Marker not found.";
        }
        MapMarker updated = change.apply(existing);
        String validation = validate(updated);
        if (validation != null) {
            return validation;
        }
        markers.put(id, updated);
        markDirtyAndPublish();
        return null;
    }

    // --- queries -----------------------------------------------------------

    public Optional<MapMarker> byId(String id) {
        return Optional.ofNullable(markers.get(id));
    }

    /** Resolves a user reference: exact id, else unique name match (case-insensitive). */
    public Optional<MapMarker> resolve(String reference) {
        MapMarker byId = markers.get(reference);
        if (byId != null) {
            return Optional.of(byId);
        }
        List<MapMarker> nameMatches = new ArrayList<>();
        for (MapMarker marker : markers.values()) {
            if (marker.name().equalsIgnoreCase(reference)) {
                nameMatches.add(marker);
            }
        }
        return nameMatches.size() == 1 ? Optional.of(nameMatches.get(0)) : Optional.empty();
    }

    public List<MapMarker> all() {
        return List.copyOf(markers.values());
    }

    public int count() {
        return markers.size();
    }

    // --- internals ---------------------------------------------------------

    private void markDirtyAndPublish() {
        dirty.set(true);
        publish();
    }

    private void publish() {
        List<Item> items = new ArrayList<>(markers.size());
        for (MapMarker marker : markers.values()) {
            if (!marker.visible() || config.disabledWorlds().contains(marker.dimensionSlug())) {
                continue;
            }
            String bannerHash = marker.bannerDesign() == null ? null
                    : bannerIconHashResolver.apply(marker.bannerDesign());
            items.add(new Item(marker, config.showCreator(), config.showCoordinates(), bannerHash));
        }
        layer.replaceAll(items);
    }

    /** @return error message or null when the marker is valid */
    static String validate(MapMarker marker) {
        String name = marker.name();
        if (name == null || name.isBlank()) {
            return "Marker names cannot be empty.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Marker names are limited to " + MAX_NAME_LENGTH + " characters.";
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            return "Marker names cannot contain control characters.";
        }
        if (marker.description() != null && marker.description().length() > MAX_DESCRIPTION_LENGTH) {
            return "Descriptions are limited to " + MAX_DESCRIPTION_LENGTH + " characters.";
        }
        if (marker.category() != null && (marker.category().length() > MAX_CATEGORY_LENGTH
                || !marker.category().matches("[a-z0-9_\\-]+"))) {
            return "Categories: lowercase letters, digits, '-' and '_' only (max "
                    + MAX_CATEGORY_LENGTH + " chars).";
        }
        if (Math.abs(marker.x()) > 30_000_000 || Math.abs(marker.z()) > 30_000_000
                || marker.y() < -2048 || marker.y() > 2048) {
            return "Marker position is outside the world.";
        }
        return null;
    }

    private static JsonObject toJson(MapMarker marker) {
        JsonObject o = new JsonObject();
        o.addProperty("id", marker.id());
        o.addProperty("world", marker.dimensionSlug());
        o.addProperty("name", marker.name());
        o.addProperty("icon", marker.icon());
        o.addProperty("x", marker.x());
        o.addProperty("y", marker.y());
        o.addProperty("z", marker.z());
        o.addProperty("description", marker.description());
        o.addProperty("category", marker.category());
        o.addProperty("color", marker.colorRgb());
        o.addProperty("creator", marker.creator() == null ? null : marker.creator().toString());
        o.addProperty("creatorName", marker.creatorName());
        o.addProperty("created", marker.createdAtEpochMs());
        o.addProperty("updated", marker.updatedAtEpochMs());
        o.addProperty("visible", marker.visible());
        o.addProperty("source", marker.source());
        o.addProperty("bannerDesign", marker.bannerDesign());
        return o;
    }

    private static MapMarker fromJson(JsonObject o) {
        try {
            MapMarker marker = new MapMarker(
                    o.get("id").getAsString(),
                    o.get("world").getAsString(),
                    o.get("name").getAsString(),
                    IconLibrary.validateOrFallback(o.get("icon").getAsString()),
                    o.get("x").getAsInt(),
                    o.get("y").getAsInt(),
                    o.get("z").getAsInt(),
                    optString(o, "description"),
                    optString(o, "category"),
                    o.has("color") && !o.get("color").isJsonNull() ? o.get("color").getAsInt() : null,
                    optString(o, "creator") != null ? UUID.fromString(o.get("creator").getAsString()) : null,
                    optString(o, "creatorName"),
                    o.get("created").getAsLong(),
                    o.get("updated").getAsLong(),
                    !o.has("visible") || o.get("visible").getAsBoolean(),
                    o.has("source") ? o.get("source").getAsString() : MapMarker.SOURCE_COMMAND,
                    optString(o, "bannerDesign"));
            return validate(marker) == null ? marker : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
