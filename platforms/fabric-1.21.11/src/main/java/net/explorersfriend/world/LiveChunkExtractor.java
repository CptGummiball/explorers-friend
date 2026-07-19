package net.explorersfriend.world;

import net.explorersfriend.color.TintType;
import net.explorersfriend.render.SurfaceCompositor;
import net.explorersfriend.render.TileChunkData;
import net.explorersfriend.util.ColorMath;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Produces {@link TileChunkData} from a <em>loaded</em> chunk. Runs exclusively on the
 * server thread (called from the budgeted end-of-tick snapshotter or the chunk-unload
 * hook) and is written to be fast and allocation-light: one reused
 * {@code BlockPos.Mutable}, flat array lookups via {@link StateColorTable}, short
 * descents thanks to the WORLD_SURFACE heightmap.
 *
 * <p>The column logic mirrors {@code RegionChunkExtractor} exactly (shared
 * {@link SurfaceCompositor}), so live and disk renders are pixel-identical.</p>
 */
public final class LiveChunkExtractor {

    private final StateColorTable colors;
    private final BiomeTintTable biomes;
    private final boolean waterDepthShading;

    public LiveChunkExtractor(StateColorTable colors, BiomeTintTable biomes, boolean waterDepthShading) {
        this.colors = colors;
        this.biomes = biomes;
        this.waterDepthShading = waterDepthShading;
    }

    public TileChunkData extract(ServerWorld world, WorldChunk chunk) {
        Registry<Biome> biomeRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        boolean hasCeiling = world.getDimension().hasCeiling();
        int minY = world.getBottomY();
        ChunkPos chunkPos = chunk.getPos();
        Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        BlockPos.Mutable pos = new BlockPos.Mutable();

        int[] colorsOut = new int[256];
        int[] heights = new int[256];
        java.util.Arrays.fill(heights, TileChunkData.EMPTY_HEIGHT);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = heightmap.get(x, z) - 1;
                if (top < minY) {
                    continue;
                }
                extractColumn(world, chunk, biomeRegistry, pos,
                        chunkPos.getStartX() + x, chunkPos.getStartZ() + z,
                        top, minY, hasCeiling, z * 16 + x, colorsOut, heights);
            }
        }

        int[] westHeights = borderHeights(world, chunkPos.x - 1, chunkPos.z, true);
        int[] northHeights = borderHeights(world, chunkPos.x, chunkPos.z - 1, false);
        return new TileChunkData(chunkPos.x, chunkPos.z, colorsOut, heights, westHeights, northHeights);
    }

    private void extractColumn(ServerWorld world, WorldChunk chunk, Registry<Biome> biomeRegistry,
                               BlockPos.Mutable pos, int blockX, int blockZ, int top, int minY,
                               boolean hasCeiling, int column, int[] colorsOut, int[] heights) {
        int y = top;
        if (hasCeiling) {
            // Skip the bedrock roof: descend to the first air pocket first.
            while (y >= minY && !chunk.getBlockState(pos.set(blockX, y, blockZ)).isAir()) {
                y--;
            }
        }
        int waterDepth = 0;
        int surfaceY = TileChunkData.EMPTY_HEIGHT;
        int translucentColor = 0;
        int translucentLayers = 0;
        boolean haveTranslucent = false;

        while (y >= minY) {
            BlockState state = chunk.getBlockState(pos.set(blockX, y, blockZ));
            if (state.isAir()) {
                y--;
                continue;
            }
            int stateId = Block.STATE_IDS.getRawId(state);
            FluidState fluid = state.getFluidState();
            boolean waterHere = !fluid.isEmpty() && fluid.isIn(FluidTags.WATER);
            boolean excluded = colors.excluded(stateId);
            int baseColor = colors.argb(stateId);
            if (waterHere) {
                if (surfaceY == TileChunkData.EMPTY_HEIGHT) {
                    surfaceY = y;
                }
                waterDepth++;
                boolean pureFluidBlock = state.getBlock() == net.minecraft.block.Blocks.WATER;
                if (pureFluidBlock || excluded || baseColor == 0) {
                    y--;
                    continue;
                }
                // waterlogged visible block: treat as ground under water
            }
            if (excluded || baseColor == 0) {
                y--;
                continue;
            }
            int alpha = ColorMath.alpha(baseColor);
            int biomeRawId = biomeRegistry.getRawId(world.getBiome(pos).value());
            int tinted = applyTint(baseColor, colors.tint(stateId), biomeRawId);
            if (!waterHere && alpha < 255 && translucentLayers < SurfaceCompositor.MAX_TRANSLUCENT_LAYERS) {
                translucentColor = haveTranslucent
                        ? SurfaceCompositor.stackTranslucent(translucentColor, tinted)
                        : tinted;
                haveTranslucent = true;
                translucentLayers++;
                if (surfaceY == TileChunkData.EMPTY_HEIGHT) {
                    surfaceY = y;
                }
                y--;
                continue;
            }
            if (surfaceY == TileChunkData.EMPTY_HEIGHT) {
                surfaceY = y;
            }
            int ground = 0xFF000000 | tinted;
            if (waterDepth > 0) {
                ground = SurfaceCompositor.overlayWater(ground,
                        colors.nameView().getOrDefault("minecraft:water",
                                net.explorersfriend.render.RenderPalette.BlockInfo.INVISIBLE).argb(),
                        biomes.water(biomeRawId), waterDepth, waterDepthShading);
            }
            if (haveTranslucent) {
                ground = ColorMath.blendOver(translucentColor, ground & 0xFFFFFF);
            }
            colorsOut[column] = ground;
            heights[column] = surfaceY;
            return;
        }
        if (waterDepth > 0 || haveTranslucent) {
            int biomeRawId = biomeRegistry.getRawId(
                    world.getBiome(pos.set(blockX, Math.max(minY, surfaceY), blockZ)).value());
            int base = waterDepth > 0
                    ? SurfaceCompositor.overlayWater(0xFF202020,
                    colors.nameView().getOrDefault("minecraft:water",
                            net.explorersfriend.render.RenderPalette.BlockInfo.INVISIBLE).argb(),
                    biomes.water(biomeRawId), waterDepth, waterDepthShading)
                    : ColorMath.blendOver(translucentColor, 0x202020);
            colorsOut[column] = base;
            heights[column] = surfaceY;
        }
    }

    private int applyTint(int baseArgb, TintType tint, int biomeRawId) {
        return SurfaceCompositor.applyTint(baseArgb, tint,
                biomes.grass(biomeRawId), biomes.foliage(biomeRawId), biomes.water(biomeRawId));
    }

    /** Border heights from a neighbouring chunk when it is loaded (no chunk loads!). */
    private int[] borderHeights(ServerWorld world, int chunkX, int chunkZ, boolean eastEdge) {
        Chunk neighbour = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (neighbour == null) {
            return null;
        }
        Heightmap heightmap = neighbour.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) {
            out[i] = eastEdge ? heightmap.get(15, i) - 1 : heightmap.get(i, 15) - 1;
        }
        return out;
    }
}
