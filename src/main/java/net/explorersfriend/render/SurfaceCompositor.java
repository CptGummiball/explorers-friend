package net.explorersfriend.render;

import net.explorersfriend.color.TintType;
import net.explorersfriend.util.ColorMath;

/**
 * Shared column color composition used by both extraction paths (live chunks and
 * region files), so a chunk renders identically no matter which source produced it.
 * Pure static math.
 */
public final class SurfaceCompositor {

    public static final int MAX_TRANSLUCENT_LAYERS = 4;

    private SurfaceCompositor() {
    }

    /** Applies the biome tint for the block's tint category. */
    public static int applyTint(int baseArgb, TintType tint, int grassRgb, int foliageRgb, int waterRgb) {
        return switch (tint) {
            case GRASS -> ColorMath.multiply(baseArgb, grassRgb);
            case FOLIAGE -> ColorMath.multiply(baseArgb, foliageRgb);
            case WATER -> ColorMath.multiply(baseArgb, waterRgb);
            case NONE -> baseArgb;
        };
    }

    /**
     * Water overlay over the ground color; opacity grows with depth when depth shading
     * is enabled ({@code alpha = 150 + 5·depth}, capped), fixed otherwise.
     */
    public static int overlayWater(int groundArgb, int waterBaseArgb, int waterTintRgb,
                                   int waterDepth, boolean depthShading) {
        int waterRgb = ColorMath.multiply(waterBaseArgb == 0 ? 0xFFBFBFBF : waterBaseArgb, waterTintRgb) & 0xFFFFFF;
        int alpha = depthShading ? Math.min(230, 150 + waterDepth * 5) : 180;
        return ColorMath.blendOver((alpha << 24) | waterRgb, groundArgb & 0xFFFFFF);
    }

    /** Stacks a lower translucent layer under an upper one, accumulating coverage. */
    public static int stackTranslucent(int upper, int lower) {
        int upperAlpha = ColorMath.alpha(upper);
        int combined = ColorMath.blendOver(upper, lower & 0xFFFFFF);
        int lowerAlpha = ColorMath.alpha(lower);
        int outAlpha = Math.min(255, upperAlpha + lowerAlpha * (255 - upperAlpha) / 255);
        return (outAlpha << 24) | (combined & 0xFFFFFF);
    }
}
