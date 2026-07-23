package net.explorersfriend.spigot;

import net.explorersfriend.render.RenderPalette;
import net.explorersfriend.render.SurfaceCompositor;
import net.explorersfriend.render.TileChunkData;
import net.explorersfriend.util.ColorMath;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.util.HashMap;
import java.util.Map;

/**
 * Live-update extractor for the Spigot/Paper backend: turns a thread-safe Bukkit
 * {@link ChunkSnapshot} into the neutral {@link TileChunkData}. Behaviour mirrors
 * the region-file extractor (water depth accumulation, translucent compositing,
 * biome tints); the snapshot is taken on the main thread, extraction runs on
 * workers. Material and biome keys are cached to avoid per-block string building.
 */
final class BukkitChunkExtractor {

    private final RenderPalette palette;
    private final boolean waterDepthShading;
    private final Map<Material, RenderPalette.BlockInfo> materialInfo = new HashMap<>();
    private final Map<Biome, String> biomeKeys = new HashMap<>();

    BukkitChunkExtractor(RenderPalette palette, boolean waterDepthShading) {
        this.palette = palette;
        this.waterDepthShading = waterDepthShading;
    }

    private RenderPalette.BlockInfo info(Material material) {
        return materialInfo.computeIfAbsent(material,
                m -> palette.blockInfo(m.getKey().toString()));
    }

    private String biomeKey(Biome biome) {
        return biomeKeys.computeIfAbsent(biome, b -> b.getKey().toString());
    }

    synchronized TileChunkData extract(ChunkSnapshot snapshot, int minY) {
        int[] colors = new int[256];
        int[] heights = new int[256];
        int maxY = 319;   // upper bound is only a scan start; heightmap trims it
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = (z << 4) | x;
                heights[index] = TileChunkData.EMPTY_HEIGHT;
                int y = Math.min(maxY, snapshot.getHighestBlockYAt(x, z));
                int waterDepth = 0;
                int waterBase = 0;
                String waterBiome = null;
                int composed = 0;
                int translucentLayers = 0;
                while (y >= minY) {
                    Material material = snapshot.getBlockType(x, y, z);
                    RenderPalette.BlockInfo info = info(material);
                    if (info.excluded() || info.argb() == 0) {
                        y--;
                        continue;
                    }
                    if (info.water()) {
                        if (waterDepth == 0) {
                            waterBase = info.argb();
                            waterBiome = biomeKey(snapshot.getBiome(x, y, z));
                        }
                        waterDepth++;
                        y--;
                        continue;
                    }
                    String biome = biomeKey(snapshot.getBiome(x, y, z));
                    int argb = SurfaceCompositor.applyTint(info.argb(), info.tint(),
                            palette.grassTint(biome), palette.foliageTint(biome),
                            palette.waterTint(biome));
                    int alpha = (argb >>> 24) & 0xFF;
                    if (alpha < 255 && translucentLayers < 4) {
                        composed = composed == 0 ? argb : ColorMath.blendOver(composed, argb);
                        translucentLayers++;
                        y--;
                        continue;
                    }
                    int ground = composed == 0 ? argb : ColorMath.blendOver(composed, argb);
                    if (waterDepth > 0) {
                        ground = SurfaceCompositor.overlayWater(ground, waterBase,
                                palette.waterTint(waterBiome), waterDepth, waterDepthShading);
                    }
                    colors[index] = 0xFF000000 | (ground & 0xFFFFFF);
                    heights[index] = waterDepth > 0 ? y + waterDepth : y;
                    break;
                }
                if (heights[index] == TileChunkData.EMPTY_HEIGHT && waterDepth > 0) {
                    // water column all the way down (ocean over void edge case)
                    int ground = SurfaceCompositor.overlayWater(0xFF000000, waterBase,
                            palette.waterTint(waterBiome), waterDepth, waterDepthShading);
                    colors[index] = 0xFF000000 | (ground & 0xFFFFFF);
                    heights[index] = minY + waterDepth;
                }
            }
        }
        return new TileChunkData(snapshot.getX(), snapshot.getZ(), colors, heights, null, null);
    }
}
