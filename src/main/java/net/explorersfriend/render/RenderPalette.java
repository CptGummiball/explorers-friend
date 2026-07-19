package net.explorersfriend.render;

import net.explorersfriend.color.TintType;

/**
 * Everything the renderer needs to know about block and biome colors, keyed by plain
 * identifier strings so that region-file rendering (which only sees NBT names) and the
 * unit tests can share one abstraction. Implementations must be thread-safe; lookups
 * are memoized per chunk-section palette entry by the extractors, so they may be
 * moderately expensive.
 */
public interface RenderPalette {

    /** Render-relevant properties of one block. {@code argb == 0} means invisible. */
    record BlockInfo(int argb, TintType tint, boolean water, boolean excluded) {

        public static final BlockInfo INVISIBLE = new BlockInfo(0, TintType.NONE, false, true);
    }

    BlockInfo blockInfo(String blockName);

    /** Grass colormap RGB for a biome (no alpha). */
    int grassTint(String biomeName);

    /** Foliage colormap RGB for a biome (no alpha). */
    int foliageTint(String biomeName);

    /** Water RGB for a biome (no alpha). */
    int waterTint(String biomeName);
}
