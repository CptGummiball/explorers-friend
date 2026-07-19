package net.explorersfriend.testutil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** PNG fixtures generated in memory. */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] solidPng(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return encode(image);
    }

    /** Vertically stacked animation frames, each frame a solid color. */
    public static byte[] framesPng(int frameEdge, int... frameColors) {
        BufferedImage image = new BufferedImage(frameEdge, frameEdge * frameColors.length,
                BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frameColors.length; frame++) {
            for (int y = 0; y < frameEdge; y++) {
                for (int x = 0; x < frameEdge; x++) {
                    image.setRGB(x, frame * frameEdge + y, frameColors[frame]);
                }
            }
        }
        return encode(image);
    }

    public static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
