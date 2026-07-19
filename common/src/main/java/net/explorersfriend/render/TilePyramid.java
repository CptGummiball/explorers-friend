package net.explorersfriend.render;

/**
 * Zoom-out compositing: a parent tile at zoom {@code z+1} is assembled from up to four
 * child tiles at zoom {@code z}, each box-downsampled 2:1 into one quadrant. Missing
 * children leave their quadrant transparent. Pure array math, deterministic.
 */
public final class TilePyramid {

    public static final int TILE_SIZE = TileRenderer.TILE_SIZE;

    private TilePyramid() {
    }

    /**
     * Downsamples {@code child} (512×512) into the quadrant ({@code quadX},
     * {@code quadZ} ∈ {0,1}) of {@code parent} (512×512). Transparent input pixels are
     * excluded from the 2×2 average; a fully transparent 2×2 block stays transparent.
     */
    public static void downsampleInto(int[] child, int[] parent, int quadX, int quadZ) {
        int half = TILE_SIZE / 2;
        int offsetX = quadX * half;
        int offsetZ = quadZ * half;
        for (int z = 0; z < half; z++) {
            int childRow = (z * 2) * TILE_SIZE;
            int parentRow = (offsetZ + z) * TILE_SIZE + offsetX;
            for (int x = 0; x < half; x++) {
                int c0 = child[childRow + x * 2];
                int c1 = child[childRow + x * 2 + 1];
                int c2 = child[childRow + TILE_SIZE + x * 2];
                int c3 = child[childRow + TILE_SIZE + x * 2 + 1];
                parent[parentRow + x] = average(c0, c1, c2, c3);
            }
        }
    }

    /** Clears the quadrant (used when a child tile was deleted). */
    public static void clearQuadrant(int[] parent, int quadX, int quadZ) {
        int half = TILE_SIZE / 2;
        int offsetX = quadX * half;
        int offsetZ = quadZ * half;
        for (int z = 0; z < half; z++) {
            int row = (offsetZ + z) * TILE_SIZE + offsetX;
            java.util.Arrays.fill(parent, row, row + half, 0);
        }
    }

    static int average(int c0, int c1, int c2, int c3) {
        // manually unrolled: this runs 65k times per pyramid update, keep it allocation-free
        int count = 0;
        int r = 0;
        int g = 0;
        int b = 0;
        int a = 0;
        int alpha = c0 >>> 24;
        if (alpha != 0) {
            count++;
            a += alpha;
            r += (c0 >> 16) & 0xFF;
            g += (c0 >> 8) & 0xFF;
            b += c0 & 0xFF;
        }
        alpha = c1 >>> 24;
        if (alpha != 0) {
            count++;
            a += alpha;
            r += (c1 >> 16) & 0xFF;
            g += (c1 >> 8) & 0xFF;
            b += c1 & 0xFF;
        }
        alpha = c2 >>> 24;
        if (alpha != 0) {
            count++;
            a += alpha;
            r += (c2 >> 16) & 0xFF;
            g += (c2 >> 8) & 0xFF;
            b += c2 & 0xFF;
        }
        alpha = c3 >>> 24;
        if (alpha != 0) {
            count++;
            a += alpha;
            r += (c3 >> 16) & 0xFF;
            g += (c3 >> 8) & 0xFF;
            b += c3 & 0xFF;
        }
        if (count == 0) {
            return 0;
        }
        return ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
    }
}
