package net.explorersfriend.claims;

/**
 * Claim color resolution with the documented priority chain:
 * explicit claim color → team color → owner color → deterministic color derived from a
 * stable key → configured default. Deterministic colors are stable across restarts
 * (hash-based hue, fixed saturation/value) so a claim never changes color randomly.
 */
public final class ClaimColors {

    private ClaimColors() {
    }

    /**
     * @param explicitColor provider-supplied claim color (RGB, null/0 = absent)
     * @param teamColor     team/group color (RGB, null = absent)
     * @param ownerColor    owner color (RGB, null = absent)
     * @param stableKey     claim/owner identity for the deterministic fallback
     * @param defaultRgb    configured default
     * @return opaque RGB base color
     */
    public static int resolveBase(Integer explicitColor, Integer teamColor, Integer ownerColor,
                                  String stableKey, int defaultRgb) {
        if (explicitColor != null && (explicitColor & 0xFFFFFF) != 0) {
            return explicitColor & 0xFFFFFF;
        }
        if (teamColor != null && (teamColor & 0xFFFFFF) != 0) {
            return teamColor & 0xFFFFFF;
        }
        if (ownerColor != null && (ownerColor & 0xFFFFFF) != 0) {
            return ownerColor & 0xFFFFFF;
        }
        if (stableKey != null && !stableKey.isBlank()) {
            return deterministic(stableKey);
        }
        return defaultRgb & 0xFFFFFF;
    }

    /** Stable, well-distributed color from a key: hashed hue, fixed S/V. */
    public static int deterministic(String key) {
        int hash = key.hashCode();
        // golden-ratio scramble for good hue distribution of similar keys
        float hue = ((hash * 0x9E3779B9L) >>> 40) / (float) (1 << 24);
        return hsvToRgb(hue, 0.62f, 0.92f);
    }

    static int hsvToRgb(float hue, float saturation, float value) {
        float h = (hue - (float) Math.floor(hue)) * 6f;
        int sector = (int) h;
        float f = h - sector;
        float p = value * (1 - saturation);
        float q = value * (1 - saturation * f);
        float t = value * (1 - saturation * (1 - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> {
                r = value;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = value;
                b = p;
            }
            case 2 -> {
                r = p;
                g = value;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = value;
            }
            case 4 -> {
                r = t;
                g = p;
                b = value;
            }
            default -> {
                r = value;
                g = p;
                b = q;
            }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /** Semi-transparent fill from the base color (opacity 0..1, clamped to sane range). */
    public static int fill(int baseRgb, double opacity) {
        int alpha = (int) Math.round(Math.max(0.05, Math.min(0.9, opacity)) * 255);
        return (alpha << 24) | (baseRgb & 0xFFFFFF);
    }

    /** Fully opaque border from the base color. */
    public static int border(int baseRgb) {
        return 0xFF000000 | (baseRgb & 0xFFFFFF);
    }
}
