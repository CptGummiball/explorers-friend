package net.explorersfriend.testutil;

import net.explorersfriend.color.TintType;
import net.explorersfriend.render.RenderPalette;

/** Fixture palette shared by the region/render tests: stone gray, water, everything else invisible. */
public final class TestPalette implements RenderPalette {

    public static final TestPalette INSTANCE = new TestPalette();

    private TestPalette() {
    }

    @Override
    public BlockInfo blockInfo(String blockName) {
        return switch (blockName) {
            case "minecraft:stone" -> new BlockInfo(0xFF808080, TintType.NONE, false, false);
            case "minecraft:water" -> new BlockInfo(0xFFBFBFBF, TintType.WATER, true, false);
            case "minecraft:grass_block" -> new BlockInfo(0xFFB0B0B0, TintType.GRASS, false, false);
            default -> BlockInfo.INVISIBLE;
        };
    }

    @Override
    public int grassTint(String biomeName) {
        return 0x91BD59;
    }

    @Override
    public int foliageTint(String biomeName) {
        return 0x77AB2F;
    }

    @Override
    public int waterTint(String biomeName) {
        return 0x3F76E4;
    }
}
