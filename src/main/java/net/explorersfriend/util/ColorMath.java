package net.explorersfriend.util;

/**
 * Color arithmetic for palette extraction and tile shading.
 *
 * <p>Palette extraction (quality path) works in linear-light space: sRGB components are
 * linearized before averaging and re-encoded afterwards, which avoids the muddy,
 * too-dark results of naive sRGB averaging. Tile shading (hot path) uses fast integer
 * multiplies in sRGB space — visually fine for relief shading and allocation-free.</p>
 *
 * <p>All methods are static and thread-safe.</p>
 */
public final class ColorMath {

    /** sRGB byte -> linear float, precomputed. */
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private ColorMath() {
    }

    public static float srgbToLinear(int srgbByte) {
        return SRGB_TO_LINEAR[srgbByte & 0xFF];
    }

    public static int linearToSrgb(float linear) {
        if (linear <= 0f) {
            return 0;
        }
        if (linear >= 1f) {
            return 255;
        }
        double c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return (int) Math.round(c * 255.0);
    }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    /**
     * Representative color of a texture: alpha-weighted mean in linear space with a
     * deterministic two-pass outlier trim (second pass drops pixels farther than
     * 2.5 standard deviations from the first-pass mean — removes ore speckles and
     * single decorative pixels). Fully transparent and near-transparent pixels
     * (alpha < 8) are ignored. Returns 0 (fully transparent) if nothing is visible.
     */
    public static int representativeColor(int[] argbPixels) {
        double sumR = 0;
        double sumG = 0;
        double sumB = 0;
        double sumW = 0;
        for (int pixel : argbPixels) {
            int a = pixel >>> 24;
            if (a < 8) {
                continue;
            }
            double w = a / 255.0;
            sumR += SRGB_TO_LINEAR[(pixel >> 16) & 0xFF] * w;
            sumG += SRGB_TO_LINEAR[(pixel >> 8) & 0xFF] * w;
            sumB += SRGB_TO_LINEAR[pixel & 0xFF] * w;
            sumW += w;
        }
        if (sumW <= 0) {
            return 0;
        }
        double meanR = sumR / sumW;
        double meanG = sumG / sumW;
        double meanB = sumB / sumW;

        // Pass 1b: variance of luminance-ish distance for the trim threshold.
        double sumSq = 0;
        for (int pixel : argbPixels) {
            int a = pixel >>> 24;
            if (a < 8) {
                continue;
            }
            double dr = SRGB_TO_LINEAR[(pixel >> 16) & 0xFF] - meanR;
            double dg = SRGB_TO_LINEAR[(pixel >> 8) & 0xFF] - meanG;
            double db = SRGB_TO_LINEAR[pixel & 0xFF] - meanB;
            sumSq += (dr * dr + dg * dg + db * db) * (a / 255.0);
        }
        double stdDev = Math.sqrt(sumSq / sumW);
        double threshold = 2.5 * stdDev;
        double thresholdSq = threshold * threshold;

        // Pass 2: trimmed mean.
        double tr = 0;
        double tg = 0;
        double tb = 0;
        double tw = 0;
        double alphaSum = 0;
        int visibleCount = 0;
        for (int pixel : argbPixels) {
            int a = pixel >>> 24;
            if (a < 8) {
                continue;
            }
            visibleCount++;
            alphaSum += a;
            double lr = SRGB_TO_LINEAR[(pixel >> 16) & 0xFF];
            double lg = SRGB_TO_LINEAR[(pixel >> 8) & 0xFF];
            double lb = SRGB_TO_LINEAR[pixel & 0xFF];
            double dr = lr - meanR;
            double dg = lg - meanG;
            double db = lb - meanB;
            if (dr * dr + dg * dg + db * db > thresholdSq) {
                continue;
            }
            double w = a / 255.0;
            tr += lr * w;
            tg += lg * w;
            tb += lb * w;
            tw += w;
        }
        if (tw <= 0) { // extreme case: everything trimmed -> fall back to untrimmed mean
            tr = meanR;
            tg = meanG;
            tb = meanB;
            tw = 1;
        } else {
            tr /= tw;
            tg /= tw;
            tb /= tw;
        }
        int outAlpha = (int) Math.round(alphaSum / visibleCount);
        return argb(outAlpha, linearToSrgb((float) tr), linearToSrgb((float) tg), linearToSrgb((float) tb));
    }

    /** Fast sRGB shade for relief/depth shading; factor 256 = unchanged. Keeps alpha. */
    public static int shade(int argb, int factor256) {
        int r = Math.min(255, (((argb >> 16) & 0xFF) * factor256) >> 8);
        int g = Math.min(255, (((argb >> 8) & 0xFF) * factor256) >> 8);
        int b = Math.min(255, ((argb & 0xFF) * factor256) >> 8);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Component-wise multiply of a base color with a tint (e.g. grass colormap). Keeps base alpha. */
    public static int multiply(int baseArgb, int tintRgb) {
        int r = (((baseArgb >> 16) & 0xFF) * ((tintRgb >> 16) & 0xFF)) / 255;
        int g = (((baseArgb >> 8) & 0xFF) * ((tintRgb >> 8) & 0xFF)) / 255;
        int b = ((baseArgb & 0xFF) * (tintRgb & 0xFF)) / 255;
        return (baseArgb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Source-over blend of {@code top} onto opaque {@code bottom}; result is opaque. */
    public static int blendOver(int topArgb, int bottomRgb) {
        int a = topArgb >>> 24;
        if (a == 255) {
            return topArgb;
        }
        if (a == 0) {
            return 0xFF000000 | bottomRgb;
        }
        int inv = 255 - a;
        int r = (((topArgb >> 16) & 0xFF) * a + ((bottomRgb >> 16) & 0xFF) * inv) / 255;
        int g = (((topArgb >> 8) & 0xFF) * a + ((bottomRgb >> 8) & 0xFF) * inv) / 255;
        int b = ((topArgb & 0xFF) * a + (bottomRgb & 0xFF) * inv) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
