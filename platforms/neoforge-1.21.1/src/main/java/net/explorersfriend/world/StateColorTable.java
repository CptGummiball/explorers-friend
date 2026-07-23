package net.explorersfriend.world;

import net.explorersfriend.color.BlockColorResult;
import net.explorersfriend.color.ManualColorOverrides;
import net.explorersfriend.color.TintType;
import net.explorersfriend.render.RenderPalette;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * O(1) per-block-state render lookup for the live snapshot path: flat arrays indexed by
 * the global block-state raw id. Built once on the scan pool after the color scan
 * (registries are frozen by then, so off-thread reads are safe) and treated as
 * immutable afterwards.
 *
 * <p>Also produces the name-keyed {@link RenderPalette.BlockInfo} map shared with the
 * region-file path, guaranteeing both pipelines use identical colors.</p>
 */
public final class StateColorTable {

    private final int[] argbByState;
    private final byte[] tintByState;
    private final boolean[] excludedByState;
    private final Map<String, RenderPalette.BlockInfo> byName;

    private StateColorTable(int[] argbByState, byte[] tintByState, boolean[] excludedByState,
                            Map<String, RenderPalette.BlockInfo> byName) {
        this.argbByState = argbByState;
        this.tintByState = tintByState;
        this.excludedByState = excludedByState;
        this.byName = byName;
    }

    public static StateColorTable build(Map<String, BlockColorResult> results,
                                        Map<String, ManualColorOverrides.ColorOverride> overrides,
                                        Set<String> excludedBlocks,
                                        int unknownColor) {
        int stateCount = Block.BLOCK_STATE_REGISTRY.size();
        int[] argb = new int[stateCount];
        byte[] tint = new byte[stateCount];
        boolean[] excluded = new boolean[stateCount];
        Map<String, RenderPalette.BlockInfo> byName = new HashMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
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

            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int rawId = Block.BLOCK_STATE_REGISTRY.getId(state);
                if (rawId < 0 || rawId >= stateCount) {
                    continue;
                }
                argb[rawId] = color;
                tint[rawId] = (byte) tintType.ordinal();
                excluded[rawId] = isExcluded || state.isAir();
            }
        }
        return new StateColorTable(argb, tint, excluded, byName);
    }

    public int argb(int stateRawId) {
        return stateRawId >= 0 && stateRawId < argbByState.length ? argbByState[stateRawId] : 0;
    }

    public TintType tint(int stateRawId) {
        if (stateRawId < 0 || stateRawId >= tintByState.length) {
            return TintType.NONE;
        }
        return TintType.values()[tintByState[stateRawId]];
    }

    public boolean excluded(int stateRawId) {
        return stateRawId < 0 || stateRawId >= excludedByState.length || excludedByState[stateRawId];
    }

    /** Name-keyed view for the region-file render path. */
    public Map<String, RenderPalette.BlockInfo> nameView() {
        return byName;
    }

    public int stateCount() {
        return argbByState.length;
    }
}
