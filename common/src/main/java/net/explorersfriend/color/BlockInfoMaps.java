package net.explorersfriend.color;

import net.explorersfriend.render.RenderPalette;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the name-keyed {@link RenderPalette.BlockInfo} map for the region-file
 * render path from scan results + manual overrides + exclusions. Pure logic over
 * string block ids - platform modules that also need a state-indexed table (the
 * Fabric-era StateColorTable) layer that on top; loader-free backends (Spigot)
 * use this map directly.
 */
public final class BlockInfoMaps {

    private BlockInfoMaps() {
    }

    public static Map<String, RenderPalette.BlockInfo> build(
            Collection<String> blockIds,
            Map<String, BlockColorResult> results,
            Map<String, ManualColorOverrides.ColorOverride> overrides,
            Set<String> excludedBlocks,
            int unknownColor) {
        Map<String, RenderPalette.BlockInfo> byName = new HashMap<>();
        for (String rawId : blockIds) {
            String id = rawId.toLowerCase(Locale.ROOT);
            int color;
            TintType tintType;
            BlockColorResult result = results.get(id);
            if (result != null) {
                color = result.argb();
                tintType = result.tint();
            } else {
                color = unknownColor;
                tintType = TintType.NONE;
            }
            ManualColorOverrides.ColorOverride override = overrides.get(id);
            if (override != null) {
                color = override.argb();
                tintType = override.tint() != TintType.NONE ? override.tint() : tintType;
            }
            boolean isExcluded = excludedBlocks.contains(id);
            boolean isWater = id.equals("minecraft:water") || id.equals("minecraft:bubble_column");
            byName.put(id, isExcluded
                    ? RenderPalette.BlockInfo.INVISIBLE
                    : new RenderPalette.BlockInfo(color, tintType, isWater, false));
        }
        return byName;
    }
}
