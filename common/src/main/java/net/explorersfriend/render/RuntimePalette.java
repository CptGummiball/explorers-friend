package net.explorersfriend.render;

import net.explorersfriend.color.TintType;

import java.util.Map;

/**
 * The immutable palette handed to render workers: block name → {@link BlockInfo} and
 * biome name → precomputed tint triple. Pure data, safe on any thread; unknown block
 * names (e.g. a mod was removed but its blocks are still in the world) resolve to the
 * configured unknown color, unknown biomes to plains-like defaults.
 */
public final class RuntimePalette implements RenderPalette {

    public static final int DEFAULT_GRASS_RGB = 0x91BD59;
    public static final int DEFAULT_FOLIAGE_RGB = 0x77AB2F;
    public static final int DEFAULT_WATER_RGB = 0x3F76E4;

    /** index 0 = grass, 1 = foliage, 2 = water */
    private final Map<String, int[]> biomeTints;
    private final Map<String, BlockInfo> blocks;
    private final BlockInfo unknownBlock;

    public RuntimePalette(Map<String, BlockInfo> blocks, Map<String, int[]> biomeTints, int unknownBlockColor) {
        this.blocks = Map.copyOf(blocks);
        this.biomeTints = Map.copyOf(biomeTints);
        this.unknownBlock = new BlockInfo(unknownBlockColor, TintType.NONE, false, false);
    }

    @Override
    public BlockInfo blockInfo(String blockName) {
        BlockInfo info = blocks.get(blockName);
        return info != null ? info : unknownBlock;
    }

    @Override
    public int grassTint(String biomeName) {
        int[] tints = biomeTints.get(biomeName);
        return tints != null ? tints[0] : DEFAULT_GRASS_RGB;
    }

    @Override
    public int foliageTint(String biomeName) {
        int[] tints = biomeTints.get(biomeName);
        return tints != null ? tints[1] : DEFAULT_FOLIAGE_RGB;
    }

    @Override
    public int waterTint(String biomeName) {
        int[] tints = biomeTints.get(biomeName);
        return tints != null ? tints[2] : DEFAULT_WATER_RGB;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int biomeCount() {
        return biomeTints.size();
    }
}
