package net.explorersfriend.render;

import net.explorersfriend.util.ColorMath;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Composes {@link TileChunkData} into 512×512 base-zoom tile pixels and applies the
 * relief shading. Deterministic and allocation-conscious: the caller owns the pixel
 * buffer (one reusable {@code int[512*512]} per worker).
 *
 * <p>Relief: each column is brightened/darkened by its height difference to the west
 * and north neighbours (the classic cartographic north-west light). Water columns get
 * a gentler slope response so lakebeds do not produce noisy shading. Neighbour heights
 * across chunk borders come from {@code westHeights}/{@code northHeights} when the
 * chunk is patched individually, or from the sibling chunk inside a full-region grid.</p>
 */
public final class TileRenderer {

    public static final int TILE_SIZE = 512;

    private final boolean heightShading;

    public TileRenderer(boolean heightShading) {
        this.heightShading = heightShading;
    }

    /** Renders a full region worth of chunks into {@code pixels} (zeroed by this call). */
    public void renderRegion(Collection<TileChunkData> chunks, int regionX, int regionZ, int[] pixels) {
        java.util.Arrays.fill(pixels, 0);
        Map<Long, TileChunkData> byPosition = new HashMap<>();
        for (TileChunkData chunk : chunks) {
            byPosition.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
        for (TileChunkData chunk : chunks) {
            TileChunkData west = byPosition.get(key(chunk.chunkX() - 1, chunk.chunkZ()));
            TileChunkData north = byPosition.get(key(chunk.chunkX(), chunk.chunkZ() - 1));
            patchChunk(pixels, withBorders(chunk, west, north), regionX, regionZ);
        }
    }

    /** Fills border-height arrays from sibling chunk data when not already present. */
    private static TileChunkData withBorders(TileChunkData chunk, TileChunkData west, TileChunkData north) {
        int[] westHeights = chunk.westHeights();
        int[] northHeights = chunk.northHeights();
        if (westHeights == null && west != null) {
            westHeights = new int[16];
            for (int z = 0; z < 16; z++) {
                westHeights[z] = west.heights()[z * 16 + 15];
            }
        }
        if (northHeights == null && north != null) {
            northHeights = new int[16];
            for (int x = 0; x < 16; x++) {
                northHeights[x] = north.heights()[15 * 16 + x];
            }
        }
        if (westHeights == chunk.westHeights() && northHeights == chunk.northHeights()) {
            return chunk;
        }
        return new TileChunkData(chunk.chunkX(), chunk.chunkZ(), chunk.colors(), chunk.heights(),
                westHeights, northHeights);
    }

    /** Writes one chunk's 16×16 pixels into the region tile buffer. */
    public void patchChunk(int[] pixels, TileChunkData chunk, int regionX, int regionZ) {
        int baseX = Math.floorMod(chunk.chunkX(), 32) * 16;
        int baseZ = Math.floorMod(chunk.chunkZ(), 32) * 16;
        int[] colors = chunk.colors();
        int[] heights = chunk.heights();
        for (int z = 0; z < 16; z++) {
            int rowOffset = (baseZ + z) * TILE_SIZE + baseX;
            for (int x = 0; x < 16; x++) {
                int column = z * 16 + x;
                int color = colors[column];
                if (color == 0) {
                    pixels[rowOffset + x] = 0;
                    continue;
                }
                if (heightShading) {
                    int height = heights[column];
                    int westHeight = x > 0 ? heights[column - 1]
                            : (chunk.westHeights() != null ? chunk.westHeights()[z] : height);
                    int northHeight = z > 0 ? heights[column - 16]
                            : (chunk.northHeights() != null ? chunk.northHeights()[x] : height);
                    color = shadeByRelief(color, height, westHeight, northHeight);
                }
                pixels[rowOffset + x] = color;
            }
        }
    }

    /**
     * Relief factor from the two neighbour height differences. Higher than both
     * neighbours → up to +25% brightness; lower → down to −30%.
     */
    static int shadeByRelief(int color, int height, int westHeight, int northHeight) {
        int diff = 0;
        if (westHeight != TileChunkData.EMPTY_HEIGHT) {
            diff += Integer.signum(height - westHeight) * Math.min(4, Math.abs(height - westHeight));
        }
        if (northHeight != TileChunkData.EMPTY_HEIGHT) {
            diff += Integer.signum(height - northHeight) * Math.min(4, Math.abs(height - northHeight));
        }
        if (diff == 0) {
            return color;
        }
        int factor = 256 + diff * 10;
        factor = Math.max(178, Math.min(320, factor));
        return ColorMath.shade(color, factor);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
