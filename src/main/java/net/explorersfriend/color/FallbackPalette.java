package net.explorersfriend.color;

import java.util.Map;

/**
 * Hand-authored approximate colors for the most common vanilla blocks. Only used when
 * no vanilla client assets are available (dedicated server with asset download disabled
 * or failed) — the map stays usable instead of going gray. Values are this project's
 * own rough estimates, not imported palette data.
 */
public final class FallbackPalette {

    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("minecraft:stone", 0xFF7A7A7A),
            Map.entry("minecraft:deepslate", 0xFF4C4C50),
            Map.entry("minecraft:granite", 0xFF95655A),
            Map.entry("minecraft:diorite", 0xFFBDBDBE),
            Map.entry("minecraft:andesite", 0xFF828287),
            Map.entry("minecraft:tuff", 0xFF6C6E67),
            Map.entry("minecraft:grass_block", 0xFF7FB238),
            Map.entry("minecraft:dirt", 0xFF976D4D),
            Map.entry("minecraft:coarse_dirt", 0xFF8A6448),
            Map.entry("minecraft:podzol", 0xFF6A4A2B),
            Map.entry("minecraft:mud", 0xFF3C3A3D),
            Map.entry("minecraft:sand", 0xFFDBD3A0),
            Map.entry("minecraft:red_sand", 0xFFBE6621),
            Map.entry("minecraft:gravel", 0xFF837B7B),
            Map.entry("minecraft:sandstone", 0xFFD8CB9A),
            Map.entry("minecraft:water", 0xFF3F76E4),
            Map.entry("minecraft:lava", 0xFFD45A12),
            Map.entry("minecraft:ice", 0xFF91B5F9),
            Map.entry("minecraft:packed_ice", 0xFF7DA6E8),
            Map.entry("minecraft:blue_ice", 0xFF74A8FD),
            Map.entry("minecraft:snow", 0xFFF8FDFD),
            Map.entry("minecraft:snow_block", 0xFFF6FAFA),
            Map.entry("minecraft:oak_log", 0xFF6E5530),
            Map.entry("minecraft:spruce_log", 0xFF3B2611),
            Map.entry("minecraft:birch_log", 0xFFD7D3CB),
            Map.entry("minecraft:oak_leaves", 0xFF4A7A28),
            Map.entry("minecraft:spruce_leaves", 0xFF3D5E3D),
            Map.entry("minecraft:birch_leaves", 0xFF6A8F47),
            Map.entry("minecraft:jungle_leaves", 0xFF4C7A22),
            Map.entry("minecraft:acacia_leaves", 0xFF5E822D),
            Map.entry("minecraft:dark_oak_leaves", 0xFF3E5C1F),
            Map.entry("minecraft:oak_planks", 0xFFA8875A),
            Map.entry("minecraft:short_grass", 0xFF7FB238),
            Map.entry("minecraft:tall_grass", 0xFF7FB238),
            Map.entry("minecraft:fern", 0xFF6FA030),
            Map.entry("minecraft:mycelium", 0xFF6F6265),
            Map.entry("minecraft:netherrack", 0xFF6F3634),
            Map.entry("minecraft:soul_sand", 0xFF54402F),
            Map.entry("minecraft:soul_soil", 0xFF4C382C),
            Map.entry("minecraft:basalt", 0xFF4E4E56),
            Map.entry("minecraft:blackstone", 0xFF2A252C),
            Map.entry("minecraft:crimson_nylium", 0xFF832020),
            Map.entry("minecraft:warped_nylium", 0xFF167E86),
            Map.entry("minecraft:end_stone", 0xFFDBDF9E),
            Map.entry("minecraft:obsidian", 0xFF100F1D),
            Map.entry("minecraft:bedrock", 0xFF565656),
            Map.entry("minecraft:clay", 0xFFA0A6B2),
            Map.entry("minecraft:terracotta", 0xFF985E43),
            Map.entry("minecraft:moss_block", 0xFF596F2D),
            Map.entry("minecraft:mushroom_stem", 0xFFC9C2B8),
            Map.entry("minecraft:red_mushroom_block", 0xFFB02E26),
            Map.entry("minecraft:brown_mushroom_block", 0xFF95715B),
            Map.entry("minecraft:cactus", 0xFF58822D),
            Map.entry("minecraft:pumpkin", 0xFFC57619),
            Map.entry("minecraft:melon", 0xFF90A044),
            Map.entry("minecraft:farmland", 0xFF8A6448),
            Map.entry("minecraft:dirt_path", 0xFF947E41),
            Map.entry("minecraft:cobblestone", 0xFF767676),
            Map.entry("minecraft:mossy_cobblestone", 0xFF6A7B58),
            Map.entry("minecraft:glass", 0x40FFFFFF),
            Map.entry("minecraft:kelp", 0xFF578A2E),
            Map.entry("minecraft:seagrass", 0xFF337810),
            Map.entry("minecraft:sculk", 0xFF0D2731),
            Map.entry("minecraft:calcite", 0xFFE0E1DD),
            Map.entry("minecraft:smooth_basalt", 0xFF48484F),
            Map.entry("minecraft:amethyst_block", 0xFF8662BF),
            Map.entry("minecraft:powder_snow", 0xFFF8FDFD),
            Map.entry("minecraft:muddy_mangrove_roots", 0xFF463B2D),
            Map.entry("minecraft:mangrove_leaves", 0xFF48731C));

    private FallbackPalette() {
    }

    /** @return approximate ARGB color or {@code null} when the block is not covered. */
    public static Integer get(String blockId) {
        return COLORS.get(blockId);
    }

    public static int size() {
        return COLORS.size();
    }
}
