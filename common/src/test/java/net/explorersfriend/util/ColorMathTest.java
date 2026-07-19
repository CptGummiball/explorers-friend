package net.explorersfriend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorMathTest {

    @Test
    void srgbLinearRoundTripIsLossless() {
        for (int value = 0; value <= 255; value++) {
            assertEquals(value, ColorMath.linearToSrgb(ColorMath.srgbToLinear(value)),
                    "round trip failed for " + value);
        }
    }

    @Test
    void uniformTextureKeepsItsColor() {
        int[] pixels = new int[256];
        java.util.Arrays.fill(pixels, 0xFF336699);
        assertEquals(0xFF336699, ColorMath.representativeColor(pixels));
    }

    @Test
    void fullyTransparentPixelsAreIgnored() {
        int[] pixels = new int[64];
        java.util.Arrays.fill(pixels, 0x00FF0000); // transparent red
        pixels[0] = 0xFF00FF00; // one visible green pixel
        assertEquals(0xFF00FF00, ColorMath.representativeColor(pixels));
    }

    @Test
    void allTransparentYieldsZero() {
        assertEquals(0, ColorMath.representativeColor(new int[]{0, 0, 0x05FFFFFF}));
    }

    @Test
    void singleOutlierPixelIsTrimmed() {
        int[] pixels = new int[256];
        java.util.Arrays.fill(pixels, 0xFF808080);
        pixels[17] = 0xFFFF00FF; // magenta speckle
        int result = ColorMath.representativeColor(pixels);
        assertEquals(0xFF808080, result, "outlier should not shift the mean");
    }

    @Test
    void deterministicForSameInput() {
        int[] pixels = new int[128];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (i * 31 & 0xFF) << 16 | (i * 17 & 0xFF) << 8 | (i * 7 & 0xFF);
        }
        assertEquals(ColorMath.representativeColor(pixels), ColorMath.representativeColor(pixels.clone()));
    }

    @Test
    void linearAverageIsBrighterThanNaiveSrgbAverage() {
        // black + white in linear space averages to ~0.5 linear = 188 sRGB, not 128
        int[] pixels = {0xFF000000, 0xFFFFFFFF};
        int result = ColorMath.representativeColor(pixels);
        assertTrue(ColorMath.red(result) > 150, "expected perceptually correct bright gray, got "
                + Integer.toHexString(result));
    }

    @Test
    void shadeAndBlendBehave() {
        assertEquals(0xFF804020, ColorMath.shade(0xFF804020, 256));
        assertEquals(0xFF000000, ColorMath.shade(0xFF000000, 320));
        int blended = ColorMath.blendOver(0x80FF0000, 0x000000);
        assertEquals(0xFF, ColorMath.alpha(blended));
        assertTrue(ColorMath.red(blended) > 100 && ColorMath.red(blended) < 150);
        assertEquals(0xFF102030, ColorMath.blendOver(0x00FFFFFF, 0x102030));
    }
}
