package net.explorersfriend.color;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.util.ColorMath;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Turns raw PNG bytes into one representative ARGB color.
 *
 * <p>Defensive: image dimensions are checked from the PNG header <em>before</em> the
 * pixel data is decoded, so a decompression-bomb PNG cannot allocate huge buffers.
 * Animated textures ({@code .mcmeta} present, frames stacked vertically) either use the
 * first frame or the average over all frames, per configuration. Stateless.</p>
 */
public final class TextureSampler {

    public static final String MODE_FIRST_FRAME = "first_frame";
    public static final String MODE_AVERAGE = "average";

    private final int maxTextureEdge;
    private final String animationMode;

    public TextureSampler(int maxTextureEdge, String animationMode) {
        this.maxTextureEdge = maxTextureEdge;
        this.animationMode = animationMode;
    }

    /**
     * @param pngBytes   texture file content
     * @param mcmetaBytes matching {@code .mcmeta} content or {@code null}
     * @return representative ARGB color (0 = fully transparent texture)
     * @throws IOException for undecodable or over-limit textures
     */
    public int sample(byte[] pngBytes, byte[] mcmetaBytes) throws IOException {
        BufferedImage image = decodeChecked(pngBytes);
        int width = image.getWidth();
        int height = image.getHeight();

        boolean animated = mcmetaBytes != null && hasAnimationSection(mcmetaBytes)
                && height > width && height % width == 0;
        int sampleHeight = animated && MODE_FIRST_FRAME.equals(animationMode) ? width : height;

        int[] pixels = image.getRGB(0, 0, width, sampleHeight, null, 0, width);
        return ColorMath.representativeColor(pixels);
    }

    private BufferedImage decodeChecked(byte[] pngBytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(pngBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("not a decodable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("invalid dimensions " + width + "x" + height);
                }
                if (width > maxTextureEdge || height > maxTextureEdge * 64L) {
                    // height gets extra slack for long animation strips of small frames
                    throw new IOException("dimensions " + width + "x" + height
                            + " exceed limit (" + maxTextureEdge + ")");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("decoder returned no image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean hasAnimationSection(byte[] mcmetaBytes) {
        try {
            JsonObject root = JsonParser
                    .parseString(new String(mcmetaBytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return root.has("animation");
        } catch (Exception e) {
            return false; // broken mcmeta: treat as not animated
        }
    }

    public String animationMode() {
        return animationMode;
    }
}
