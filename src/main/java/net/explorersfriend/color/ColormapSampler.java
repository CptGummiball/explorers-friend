package net.explorersfriend.color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Samples the vanilla 256×256 grass/foliage colormap textures by biome climate, the
 * same way the game does: {@code x = (1−temperature)·255}, {@code y = (1−(downfall·temperature))·255}.
 * The colormap PNGs are read at runtime from the user's own game JAR — they are never
 * shipped with this mod. Immutable and thread-safe.
 */
public final class ColormapSampler {

    private final int[] pixels;
    private final int width;
    private final int height;
    private final int fallbackRgb;

    private ColormapSampler(int[] pixels, int width, int height, int fallbackRgb) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.fallbackRgb = fallbackRgb;
    }

    public static ColormapSampler fromPng(byte[] pngBytes, int fallbackRgb) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (image == null) {
            throw new IOException("colormap is not a decodable image");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 16 || height < 16 || width > 1024 || height > 1024) {
            throw new IOException("colormap has unexpected dimensions " + width + "x" + height);
        }
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new ColormapSampler(pixels, width, height, fallbackRgb);
    }

    /** Constant-color sampler used when no colormap asset is available. */
    public static ColormapSampler constant(int rgb) {
        return new ColormapSampler(new int[]{0xFF000000 | rgb}, 1, 1, rgb);
    }

    /** @return RGB (no alpha) for the given biome climate values. */
    public int sample(float temperature, float downfall) {
        float t = clamp01(temperature);
        float d = clamp01(downfall) * t;
        int x = (int) ((1.0f - t) * (width - 1));
        int y = (int) ((1.0f - d) * (height - 1));
        int pixel = pixels[y * width + x];
        if ((pixel >>> 24) == 0) {
            return fallbackRgb; // transparent corner of the triangle map
        }
        return pixel & 0xFFFFFF;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(value, 1f);
    }
}
