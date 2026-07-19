package net.explorersfriend.render;

/**
 * Identity of one tile job: dimension slug, zoom level (0 = base 1px/block), tile
 * coordinates. At zoom 0 a tile equals one region (512×512 blocks); each zoom-out
 * level halves the scale, so {@code tileX = regionX >> zoom} (floor division).
 */
public record TileKey(String dimensionSlug, int zoom, int tileX, int tileZ) {

    public TileKey parent() {
        return new TileKey(dimensionSlug, zoom + 1, tileX >> 1, tileZ >> 1);
    }

    public static TileKey baseForChunk(String dimensionSlug, int chunkX, int chunkZ) {
        return new TileKey(dimensionSlug, 0, chunkX >> 5, chunkZ >> 5);
    }

    public static TileKey baseForRegion(String dimensionSlug, int regionX, int regionZ) {
        return new TileKey(dimensionSlug, 0, regionX, regionZ);
    }

    @Override
    public String toString() {
        return dimensionSlug + "/z" + zoom + "/" + tileX + "_" + tileZ;
    }
}
