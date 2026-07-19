package net.explorersfriend.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.util.Log;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Tile file layout and PNG IO: {@code tiles/<dimension-slug>/<zoom>/<x>_<z>.png} plus a
 * per-dimension {@code meta.json} carrying the tile format version. All writes are
 * atomic; fully empty tiles are deleted instead of written. Thread-safe: concurrent
 * writers of the <em>same</em> tile are already serialized by the scheduler's per-tile
 * job merging.
 */
public final class TileStore {

    /** Bump when the visual tile format changes incompatibly → full re-render. */
    public static final int TILE_FORMAT_VERSION = 1;
    public static final int TILE_SIZE = TileRenderer.TILE_SIZE;

    private final Path tilesRoot;

    public TileStore(Path tilesRoot) {
        this.tilesRoot = tilesRoot;
    }

    /** Filesystem-safe slug for a dimension id ("minecraft:overworld" → "minecraft_overworld"). */
    public static String dimensionSlug(String dimensionId) {
        StringBuilder out = new StringBuilder(dimensionId.length());
        for (char c : dimensionId.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' ? c : '_');
        }
        return out.toString();
    }

    public Path tilePath(String dimensionSlug, int zoom, int tileX, int tileZ) {
        return tilesRoot.resolve(dimensionSlug).resolve(Integer.toString(zoom))
                .resolve(tileX + "_" + tileZ + ".png");
    }

    public Path dimensionDir(String dimensionSlug) {
        return tilesRoot.resolve(dimensionSlug);
    }

    /** @return tile pixels (512×512 ARGB) or {@code null} when absent or unreadable. */
    public int[] readTile(String dimensionSlug, int zoom, int tileX, int tileZ) {
        Path path = tilePath(dimensionSlug, zoom, tileX, tileZ);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) {
                Log.LOGGER.warn("[ExplorersFriend/Cache] Tile {} is not a {}px PNG; discarding",
                        path.getFileName(), TILE_SIZE);
                return null;
            }
            return image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE);
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Cache] Could not read tile {}: {}", path, e.toString());
            return null;
        }
    }

    /**
     * Writes a tile atomically; deletes the file instead when every pixel is transparent.
     *
     * @return true when a file exists after the call (false = empty tile removed/skipped)
     */
    public boolean writeTile(String dimensionSlug, int zoom, int tileX, int tileZ, int[] pixels) throws IOException {
        Path path = tilePath(dimensionSlug, zoom, tileX, tileZ);
        boolean empty = true;
        for (int pixel : pixels) {
            if (pixel != 0) {
                empty = false;
                break;
            }
        }
        if (empty) {
            Files.deleteIfExists(path);
            return false;
        }
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(image, "png", buffer);
        MoreFiles.writeAtomic(path, buffer.toByteArray());
        return true;
    }

    /**
     * Ensures the per-dimension meta file matches the current format.
     *
     * @return true when existing tiles are still valid; false when the format changed
     *         (caller schedules a full re-render)
     */
    public boolean checkOrWriteMeta(String dimensionSlug, int zoomLevels) {
        Path metaPath = dimensionDir(dimensionSlug).resolve("meta.json");
        boolean valid = false;
        if (Files.exists(metaPath)) {
            try {
                JsonObject meta = JsonParser
                        .parseString(Files.readString(metaPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                valid = meta.get("formatVersion").getAsInt() == TILE_FORMAT_VERSION
                        && meta.get("tileSize").getAsInt() == TILE_SIZE;
            } catch (Exception e) {
                Log.LOGGER.warn("[ExplorersFriend/Cache] Unreadable tile meta for {}: {}",
                        dimensionSlug, e.toString());
            }
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("formatVersion", TILE_FORMAT_VERSION);
        meta.addProperty("tileSize", TILE_SIZE);
        meta.addProperty("zoomLevels", zoomLevels);
        try {
            MoreFiles.writeAtomicUtf8(metaPath, Jsonc.GSON.toJson(meta));
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Cache] Could not write tile meta for {}: {}",
                    dimensionSlug, e.toString());
        }
        return valid;
    }

    public Path root() {
        return tilesRoot;
    }
}
