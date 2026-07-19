package net.explorersfriend.marker;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.util.Log;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * User-supplied marker icons from {@code config/explorersfriend/icons/}.
 *
 * <p>Only raster formats (PNG/JPEG) are accepted, and every file is fully decoded and
 * re-encoded to PNG before it is ever served — a crafted file can therefore at worst
 * fail to decode, never reach a browser as-is. SVG remains deliberately unsupported
 * for user content (script/XSS vector, see SECURITY_PRIVACY.md). File names become
 * icon ids ({@code custom:<name>}); names are validated against a strict allowlist
 * pattern, so request paths can never address the file system.</p>
 *
 * <p>Loading never throws: broken files are logged and skipped, and when the
 * configured count limit cuts icons off, the skipped names are logged too (no silent
 * caps). {@link #reload} publishes an immutable snapshot and registers the id set
 * with {@link IconLibrary} for validation.</p>
 */
public final class CustomIconStore {

    /** One loaded icon: re-encoded PNG bytes plus its content hash (ETag use). */
    public record Entry(byte[] png, String sha256) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg");

    private final Path dir;
    private volatile Map<String, Entry> icons = Map.of();

    public CustomIconStore(Path dir) {
        this.dir = dir;
    }

    /** Rescans the icon directory with the given settings. Never throws. */
    public synchronized void reload(MapConfig.CustomIcons settings) {
        Map<String, Entry> loaded = new TreeMap<>();
        if (settings != null && settings.enabled() && Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException e) {
                Log.LOGGER.warn("[ExplorersFriend/Icons] Cannot list {}: {}", dir, e.toString());
                files = List.of();
            }
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                String name = fileName.substring(0, dot);
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!EXTENSIONS.contains(ext)) {
                    continue;   // silently ignore readme.txt and friends
                }
                if (!NAME.matcher(name).matches()) {
                    Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': icon names must match "
                            + "[a-z0-9_-]{{1,32}}", fileName);
                    continue;
                }
                if (loaded.containsKey(name)) {
                    Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': duplicate icon name '{}'",
                            fileName, name);
                    continue;
                }
                if (loaded.size() >= settings.maxCount()) {
                    Log.LOGGER.warn("[ExplorersFriend/Icons] Limit of {} custom icons reached - "
                            + "skipping '{}' and all further files (raise markers.custom-icons.max-count)",
                            settings.maxCount(), fileName);
                    break;
                }
                Entry entry = loadOne(file, fileName, settings);
                if (entry != null) {
                    loaded.put(name, entry);
                }
            }
        }
        icons = Map.copyOf(loaded);
        IconLibrary.setCustomIcons(icons.keySet());
        if (!icons.isEmpty()) {
            Log.LOGGER.info("[ExplorersFriend/Icons] {} custom icon(s) loaded from {}",
                    icons.size(), dir.getFileName());
        }
    }

    private static Entry loadOne(Path file, String fileName, MapConfig.CustomIcons settings) {
        try {
            long size = Files.size(file);
            if (size > settings.maxFileKiB() * 1024L) {
                Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': {} KiB exceeds "
                        + "markers.custom-icons.max-file-kib ({})", fileName, size / 1024,
                        settings.maxFileKiB());
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(file)));
            if (image == null) {
                Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': not a decodable PNG/JPEG",
                        fileName);
                return null;
            }
            if (image.getWidth() > settings.maxEdgePx() || image.getHeight() > settings.maxEdgePx()
                    || image.getWidth() < 1 || image.getHeight() < 1) {
                Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': {}x{} exceeds "
                        + "markers.custom-icons.max-edge-px ({})", fileName, image.getWidth(),
                        image.getHeight(), settings.maxEdgePx());
                return null;
            }
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(argb, "png", out);
            byte[] png = out.toByteArray();
            return new Entry(png, sha256(png));
        } catch (IOException | RuntimeException e) {
            Log.LOGGER.warn("[ExplorersFriend/Icons] Skipping '{}': {}", fileName, e.toString());
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The loaded icon for a bare name (without the {@code custom:} prefix), or null. */
    public Entry get(String name) {
        return icons.get(name);
    }

    public Set<String> names() {
        return icons.keySet();
    }
}
