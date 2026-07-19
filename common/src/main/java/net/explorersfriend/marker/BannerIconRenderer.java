package net.explorersfriend.marker;

import net.explorersfriend.util.Log;
import net.explorersfriend.resource.ZipResourceSource;
import net.explorersfriend.util.Hashes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side banner icon composition. Chosen over client-side rendering because the
 * real pattern shapes live in the vanilla texture assets (which the mod already has
 * access to via the cached client JAR) — recreating ~40 pattern artworks in
 * JavaScript would be infeasible, while compositing masks here costs microseconds,
 * deduplicates identical designs by content hash, and ships as tiny cached PNGs.
 *
 * <p>Design string → 20×40 PNG: base texture tinted with the base dye color, then each
 * pattern layer's mask tinted and alpha-blended. Results are memoized (design → hash →
 * bytes, LRU-capped). Falls back to a simple striped banner when assets are missing.
 * Thread-safe.</p>
 */
public final class BannerIconRenderer {

    private static final int FACE_X = 1;
    private static final int FACE_Y = 1;
    private static final int FACE_W = 20;
    private static final int FACE_H = 40;
    private static final int MAX_CACHE = 512;

    /** Dye color RGB values (this project's own table). */
    private static final Map<String, Integer> DYE_RGB = Map.ofEntries(
            Map.entry("white", 0xF9FFFE), Map.entry("orange", 0xF9801D),
            Map.entry("magenta", 0xC74EBD), Map.entry("light_blue", 0x3AB3DA),
            Map.entry("yellow", 0xFED83D), Map.entry("lime", 0x80C71F),
            Map.entry("pink", 0xF38BAA), Map.entry("gray", 0x474F52),
            Map.entry("light_gray", 0x9D9D97), Map.entry("cyan", 0x169C9C),
            Map.entry("purple", 0x8932B8), Map.entry("blue", 0x3C44AA),
            Map.entry("brown", 0x835432), Map.entry("green", 0x5E7C16),
            Map.entry("red", 0xB02E26), Map.entry("black", 0x1D1D21));

    private final Path vanillaJar;
    private volatile ZipResourceSource assets;
    private final Map<String, String> hashByDesign = new LinkedHashMap<>();
    private final Map<String, byte[]> pngByHash = new LinkedHashMap<>(16, 0.75f, true);

    public BannerIconRenderer(Path vanillaJarOrNull) {
        this.vanillaJar = vanillaJarOrNull;
    }

    /** @return content hash for a design (rendering lazily), or null when disabled. */
    public synchronized String hashFor(String design) {
        if (design == null) {
            return null;
        }
        String cached = hashByDesign.get(design);
        if (cached != null) {
            return cached;
        }
        byte[] png = render(design);
        String hash = Hashes.sha256Hex(png).substring(0, 16);
        if (pngByHash.size() >= MAX_CACHE) {
            var iterator = pngByHash.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        pngByHash.put(hash, png);
        if (hashByDesign.size() >= MAX_CACHE * 2) {
            hashByDesign.clear();
        }
        hashByDesign.put(design, hash);
        return hash;
    }

    /** @return PNG bytes for a previously issued hash, or null. */
    public synchronized byte[] pngForHash(String hash) {
        return pngByHash.get(hash);
    }

    private byte[] render(String design) {
        String[] parts = design.split(";");
        String baseColorName = parts[0].startsWith("base=") ? parts[0].substring(5) : "white";
        int baseRgb = DYE_RGB.getOrDefault(baseColorName, 0xF9FFFE);
        BufferedImage out = new BufferedImage(FACE_W, FACE_H, BufferedImage.TYPE_INT_ARGB);
        try {
            int[] baseMask = readMask("assets/minecraft/textures/entity/banner/base.png");
            applyLayer(out, baseMask, baseRgb, true);
            for (int i = 1; i < parts.length; i++) {
                int colon = parts[i].lastIndexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String pattern = parts[i].substring(0, colon);
                if (!pattern.matches("[a-z0-9_\\-/]+")) {
                    continue;
                }
                int layerRgb = DYE_RGB.getOrDefault(parts[i].substring(colon + 1), 0x1D1D21);
                try {
                    int[] mask = readMask("assets/minecraft/textures/entity/banner/" + pattern + ".png");
                    applyLayer(out, mask, layerRgb, false);
                } catch (IOException e) {
                    Log.LOGGER.debug("[ExplorersFriend/Banners] Pattern '{}' unavailable: {}",
                            pattern, e.getMessage());
                }
            }
        } catch (IOException e) {
            renderFallback(out, baseRgb, parts);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG encoding failed", e);
        }
    }

    /** Reads the 20×40 face region of a banner texture as ARGB pixels. */
    private int[] readMask(String assetPath) throws IOException {
        ZipResourceSource source = openAssets();
        if (source == null) {
            throw new IOException("no vanilla assets available");
        }
        byte[] data = source.read(assetPath);
        if (data == null) {
            throw new IOException(assetPath + " missing");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() < FACE_X + FACE_W || image.getHeight() < FACE_Y + FACE_H) {
            throw new IOException(assetPath + " has unexpected dimensions");
        }
        return image.getRGB(FACE_X, FACE_Y, FACE_W, FACE_H, null, 0, FACE_W);
    }

    /** Tints a grayscale/alpha mask and blends it onto the output. */
    private static void applyLayer(BufferedImage out, int[] mask, int rgb, boolean opaqueBase) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        for (int y = 0; y < FACE_H; y++) {
            for (int x = 0; x < FACE_W; x++) {
                int maskPixel = mask[y * FACE_W + x];
                int alpha = maskPixel >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int luminance = ((maskPixel >> 16 & 0xFF) + (maskPixel >> 8 & 0xFF) + (maskPixel & 0xFF)) / 3;
                int tr = r * luminance / 255;
                int tg = g * luminance / 255;
                int tb = b * luminance / 255;
                if (opaqueBase || alpha == 255) {
                    out.setRGB(x, y, 0xFF000000 | (tr << 16) | (tg << 8) | tb);
                } else {
                    int existing = out.getRGB(x, y);
                    int inverse = 255 - alpha;
                    int nr = (tr * alpha + ((existing >> 16) & 0xFF) * inverse) / 255;
                    int ng = (tg * alpha + ((existing >> 8) & 0xFF) * inverse) / 255;
                    int nb = (tb * alpha + (existing & 0xFF) * inverse) / 255;
                    out.setRGB(x, y, 0xFF000000 | (nr << 16) | (ng << 8) | nb);
                }
            }
        }
    }

    /** No assets: base color + one stripe per layer color, still recognizable. */
    private static void renderFallback(BufferedImage out, int baseRgb, String[] parts) {
        for (int y = 0; y < FACE_H; y++) {
            for (int x = 0; x < FACE_W; x++) {
                out.setRGB(x, y, 0xFF000000 | baseRgb);
            }
        }
        int stripe = 0;
        for (int i = 1; i < parts.length && stripe < 6; i++) {
            int colon = parts[i].lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            int rgb = DYE_RGB.getOrDefault(parts[i].substring(colon + 1), 0x1D1D21);
            int yStart = 4 + stripe * 6;
            for (int y = yStart; y < Math.min(FACE_H, yStart + 4); y++) {
                for (int x = 2; x < FACE_W - 2; x++) {
                    out.setRGB(x, y, 0xFF000000 | rgb);
                }
            }
            stripe++;
        }
    }

    private ZipResourceSource openAssets() {
        ZipResourceSource current = assets;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (assets == null && vanillaJar != null) {
                try {
                    assets = new ZipResourceSource(vanillaJar, "vanilla", "vanilla assets (banner icons)",
                            100_000, 8L * 1024 * 1024);
                } catch (IOException e) {
                    Log.LOGGER.warn("[ExplorersFriend/Banners] Vanilla assets unavailable ({}); "
                            + "banner icons use the striped fallback", e.toString());
                }
            }
            return assets;
        }
    }

    public void close() {
        ZipResourceSource current = assets;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // shutdown path
            }
        }
    }
}
