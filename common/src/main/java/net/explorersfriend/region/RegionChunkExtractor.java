package net.explorersfriend.region;

import net.explorersfriend.render.RenderPalette;
import net.explorersfriend.render.SurfaceCompositor;
import net.explorersfriend.render.TileChunkData;
import net.explorersfriend.util.ColorMath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns parsed chunk NBT (1.18+ "sections" format) into {@link TileChunkData}.
 * Pure logic over the NBT tree and a {@link RenderPalette}; runs on render workers.
 *
 * <p>Per column the extractor descends from the WORLD_SURFACE heightmap (or a section
 * scan when the heightmap is missing): water and waterlogged blocks accumulate depth,
 * invisible/excluded blocks are skipped, translucent blocks (alpha &lt; 255) are
 * composited over the first opaque block below (up to 4 layers). Biome tint and a
 * depth-eased water overlay are applied here, so the renderer only adds relief shading.</p>
 */
public final class RegionChunkExtractor {

    /** Per-dimension extraction settings. */
    public record Settings(boolean waterDepthShading, boolean hasCeiling) {
    }

    private final RenderPalette palette;
    private final Settings settings;

    public RegionChunkExtractor(RenderPalette palette, Settings settings) {
        this.palette = palette;
        this.settings = settings;
    }

    /**
     * @return extracted render data, or {@code null} for chunks that are not fully
     *         generated ({@code Status != minecraft:full}) or structurally unusable.
     */
    public TileChunkData extract(Map<String, Object> chunkRoot) {
        String status = NbtReader.string(chunkRoot, "Status");
        if (status != null && !status.endsWith("full")) {
            return null;
        }
        List<Object> sectionsList = NbtReader.list(chunkRoot, "sections");
        if (sectionsList == null || sectionsList.isEmpty()) {
            return null;
        }
        int chunkX = NbtReader.intValue(chunkRoot, "xPos", Integer.MIN_VALUE);
        int chunkZ = NbtReader.intValue(chunkRoot, "zPos", Integer.MIN_VALUE);
        int minSectionY = NbtReader.intValue(chunkRoot, "yPos", 0);
        if (chunkX == Integer.MIN_VALUE || chunkZ == Integer.MIN_VALUE) {
            return null;
        }
        int minY = minSectionY * 16;

        Sections sections = Sections.parse(sectionsList, minSectionY, palette);
        int maxY = sections.maxY();

        int[] surface = readWorldSurface(chunkRoot, minY);

        int[] colors = new int[256];
        int[] heights = new int[256];
        for (int i = 0; i < 256; i++) {
            heights[i] = TileChunkData.EMPTY_HEIGHT;
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = z * 16 + x;
                int startY = surface != null ? surface[column] : maxY;
                if (startY < minY) {
                    continue; // empty column
                }
                extractColumn(sections, x, z, Math.min(startY, maxY), minY, column, colors, heights);
            }
        }
        return new TileChunkData(chunkX, chunkZ, colors, heights, null, null);
    }

    private void extractColumn(Sections sections, int x, int z, int startY, int minY,
                               int column, int[] colors, int[] heights) {
        int y = startY;
        if (settings.hasCeiling()) {
            // Skip the solid roof: descend to the first air pocket before searching ground.
            while (y >= minY && !sections.infoAt(x, y, z).excluded()) {
                y--;
            }
        }
        int waterDepth = 0;
        int surfaceY = TileChunkData.EMPTY_HEIGHT;
        int translucentColor = 0;
        int translucentLayers = 0;
        boolean haveTranslucent = false;

        while (y >= minY) {
            RenderPalette.BlockInfo info = sections.infoAt(x, y, z);
            boolean waterHere = info.water() || sections.isWaterlogged(x, y, z);
            if (waterHere) {
                if (surfaceY == TileChunkData.EMPTY_HEIGHT) {
                    surfaceY = y;
                }
                waterDepth++;
                if (info.water() || info.excluded() || info.argb() == 0) {
                    y--;
                    continue; // pure water / invisible waterlogged support: keep descending
                }
                // waterlogged visible block: treat it as the ground under water
            }
            if (info.excluded() || info.argb() == 0) {
                y--;
                continue;
            }
            int alpha = ColorMath.alpha(info.argb());
            String biome = sections.biomeAt(x, y, z);
            int tinted = applyTint(info, biome);
            if (!waterHere && alpha < 255 && translucentLayers < SurfaceCompositor.MAX_TRANSLUCENT_LAYERS) {
                // glass, slime, plants with translucent average color: composite later
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
            // opaque ground (or waterlogged visible block)
            if (surfaceY == TileChunkData.EMPTY_HEIGHT) {
                surfaceY = y;
            }
            int ground = 0xFF000000 | tinted;
            if (waterDepth > 0) {
                ground = overlayWater(ground, biome, waterDepth);
            }
            if (haveTranslucent) {
                ground = ColorMath.blendOver(translucentColor, ground & 0xFFFFFF);
            }
            colors[column] = ground;
            heights[column] = surfaceY;
            return;
        }
        // No opaque ground found: keep water/translucent impression over darkness.
        if (waterDepth > 0 || haveTranslucent) {
            int base = waterDepth > 0
                    ? overlayWater(0xFF202020, sections.biomeAt(x, Math.max(minY, surfaceY), z), waterDepth)
                    : ColorMath.blendOver(translucentColor, 0x202020);
            colors[column] = base;
            heights[column] = surfaceY;
        }
    }

    private int applyTint(RenderPalette.BlockInfo info, String biome) {
        return SurfaceCompositor.applyTint(info.argb(), info.tint(),
                palette.grassTint(biome), palette.foliageTint(biome), palette.waterTint(biome));
    }

    /** Water overlay whose opacity grows with depth (bounded), tinted per biome. */
    private int overlayWater(int groundArgb, String biome, int waterDepth) {
        RenderPalette.BlockInfo water = palette.blockInfo("minecraft:water");
        return SurfaceCompositor.overlayWater(groundArgb, water.argb(), palette.waterTint(biome),
                waterDepth, settings.waterDepthShading());
    }

    /** WORLD_SURFACE heightmap → absolute top-block Y per column, or null when absent. */
    static int[] readWorldSurface(Map<String, Object> chunkRoot, int minY) {
        Map<String, Object> heightmaps = NbtReader.compound(chunkRoot, "Heightmaps");
        if (heightmaps == null) {
            return null;
        }
        long[] packed = NbtReader.longArray(heightmaps, "WORLD_SURFACE");
        if (packed == null || packed.length == 0) {
            return null;
        }
        int entriesPerLong = (256 + packed.length - 1) / packed.length;
        int bits = 64 / entriesPerLong;
        if (bits <= 0 || bits > 16) {
            return null;
        }
        long mask = (1L << bits) - 1;
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            long word = packed[i / entriesPerLong];
            int value = (int) ((word >>> ((i % entriesPerLong) * bits)) & mask);
            // Stored value is (highest block y + 1) relative to the world bottom; 0 = empty.
            out[i] = value == 0 ? Integer.MIN_VALUE : minY + value - 1;
        }
        return out;
    }

    /**
     * Parsed section stack with per-palette-entry memoized {@link RenderPalette.BlockInfo}.
     */
    static final class Sections {

        private record Section(
                RenderPalette.BlockInfo[] blockInfos,
                boolean[] waterlogged,
                long[] blockData,
                int blockBits,
                String[] biomePalette,
                long[] biomeData,
                int biomeBits) {
        }

        private final Map<Integer, Section> byY = new HashMap<>();
        private final int minSectionY;
        private int maxSectionY;

        private Sections(int minSectionY) {
            this.minSectionY = minSectionY;
            this.maxSectionY = minSectionY;
        }

        @SuppressWarnings("unchecked")
        static Sections parse(List<Object> sectionsList, int minSectionY, RenderPalette palette) {
            Sections sections = new Sections(minSectionY);
            Map<String, RenderPalette.BlockInfo> infoCache = new HashMap<>();
            for (Object sectionObj : sectionsList) {
                if (!(sectionObj instanceof Map<?, ?> rawSection)) {
                    continue;
                }
                Map<String, Object> section = (Map<String, Object>) rawSection;
                int sectionY = NbtReader.intValue(section, "Y", Integer.MIN_VALUE);
                if (sectionY == Integer.MIN_VALUE) {
                    continue;
                }
                Map<String, Object> blockStates = NbtReader.compound(section, "block_states");
                if (blockStates == null) {
                    continue;
                }
                List<Object> paletteList = NbtReader.list(blockStates, "palette");
                if (paletteList == null || paletteList.isEmpty()) {
                    continue;
                }
                RenderPalette.BlockInfo[] infos = new RenderPalette.BlockInfo[paletteList.size()];
                boolean[] waterlogged = new boolean[paletteList.size()];
                boolean allInvisible = true;
                for (int i = 0; i < paletteList.size(); i++) {
                    Map<String, Object> entry = paletteList.get(i) instanceof Map<?, ?> m
                            ? (Map<String, Object>) m : Map.of();
                    String name = NbtReader.string(entry, "Name");
                    if (name == null || name.endsWith("air")) {
                        infos[i] = RenderPalette.BlockInfo.INVISIBLE;
                        continue;
                    }
                    infos[i] = infoCache.computeIfAbsent(name, palette::blockInfo);
                    Map<String, Object> properties = NbtReader.compound(entry, "Properties");
                    waterlogged[i] = properties != null
                            && "true".equals(NbtReader.string(properties, "waterlogged"));
                    if (!infos[i].excluded() || infos[i].water() || waterlogged[i]) {
                        allInvisible = false;
                    }
                }
                if (allInvisible) {
                    continue; // pure air section
                }
                long[] blockData = NbtReader.longArray(blockStates, "data");
                int blockBits = bitsFor(paletteList.size(), 4);

                String[] biomePalette = null;
                long[] biomeData = null;
                int biomeBits = 0;
                Map<String, Object> biomes = NbtReader.compound(section, "biomes");
                if (biomes != null) {
                    List<Object> biomeList = NbtReader.list(biomes, "palette");
                    if (biomeList != null && !biomeList.isEmpty()) {
                        biomePalette = new String[biomeList.size()];
                        for (int i = 0; i < biomeList.size(); i++) {
                            biomePalette[i] = biomeList.get(i) instanceof String s ? s : "minecraft:plains";
                        }
                        biomeData = NbtReader.longArray(biomes, "data");
                        biomeBits = bitsFor(biomeList.size(), 1);
                    }
                }
                sections.byY.put(sectionY, new Section(infos, waterlogged, blockData, blockBits,
                        biomePalette, biomeData, biomeBits));
                sections.maxSectionY = Math.max(sections.maxSectionY, sectionY);
            }
            return sections;
        }

        private static int bitsFor(int paletteSize, int minBits) {
            if (paletteSize <= 1) {
                return 0;
            }
            return Math.max(minBits, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        }

        int maxY() {
            return maxSectionY * 16 + 15;
        }

        RenderPalette.BlockInfo infoAt(int x, int y, int z) {
            Section section = byY.get(Math.floorDiv(y, 16));
            if (section == null) {
                return RenderPalette.BlockInfo.INVISIBLE;
            }
            int index = paletteIndex(section.blockData(), section.blockBits(),
                    localIndex(x, y, z), section.blockInfos().length);
            return section.blockInfos()[index];
        }

        boolean isWaterlogged(int x, int y, int z) {
            Section section = byY.get(Math.floorDiv(y, 16));
            if (section == null) {
                return false;
            }
            int index = paletteIndex(section.blockData(), section.blockBits(),
                    localIndex(x, y, z), section.waterlogged().length);
            return section.waterlogged()[index];
        }

        String biomeAt(int x, int y, int z) {
            Section section = byY.get(Math.floorDiv(y, 16));
            if (section == null || section.biomePalette() == null) {
                return "minecraft:plains";
            }
            int localY = Math.floorMod(y, 16);
            int biomeIndex = ((localY >> 2) * 4 + (Math.floorMod(z, 16) >> 2)) * 4 + (Math.floorMod(x, 16) >> 2);
            int index = paletteIndex(section.biomeData(), section.biomeBits(),
                    biomeIndex, section.biomePalette().length);
            return section.biomePalette()[index];
        }

        private static int localIndex(int x, int y, int z) {
            return (Math.floorMod(y, 16) * 16 + Math.floorMod(z, 16)) * 16 + Math.floorMod(x, 16);
        }

        private static int paletteIndex(long[] data, int bits, int position, int paletteSize) {
            if (data == null || bits == 0) {
                return 0;
            }
            int entriesPerLong = 64 / bits;
            int wordIndex = position / entriesPerLong;
            if (wordIndex >= data.length) {
                return 0;
            }
            long word = data[wordIndex];
            int value = (int) ((word >>> ((position % entriesPerLong) * bits)) & ((1L << bits) - 1));
            return value < paletteSize ? value : 0;
        }
    }
}
