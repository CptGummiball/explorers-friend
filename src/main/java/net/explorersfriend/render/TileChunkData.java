package net.explorersfriend.render;

/**
 * Immutable render input for one chunk: the fully composed per-column base color
 * (block color + biome tint + water overlay already applied; 0 = nothing visible),
 * the relief height per column (water surface for flooded columns), and optional
 * border heights of the western/northern neighbour columns for seamless relief
 * shading across chunk boundaries ({@code null} = neighbour unknown, shade neutral).
 *
 * <p>~1.3 KiB per chunk; produced either on the server thread (live snapshot, budgeted)
 * or on a render worker (region file), consumed only by render workers.</p>
 */
public record TileChunkData(
        int chunkX,
        int chunkZ,
        int[] colors,
        int[] heights,
        int[] westHeights,
        int[] northHeights) {

    public static final int EMPTY_HEIGHT = Integer.MIN_VALUE;

    public TileChunkData {
        if (colors.length != 256 || heights.length != 256) {
            throw new IllegalArgumentException("column arrays must have 256 entries");
        }
        if (westHeights != null && westHeights.length != 16) {
            throw new IllegalArgumentException("westHeights must have 16 entries");
        }
        if (northHeights != null && northHeights.length != 16) {
            throw new IllegalArgumentException("northHeights must have 16 entries");
        }
    }

    public boolean isEmpty() {
        for (int color : colors) {
            if (color != 0) {
                return false;
            }
        }
        return true;
    }
}
