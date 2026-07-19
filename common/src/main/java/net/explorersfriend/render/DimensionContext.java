package net.explorersfriend.render;

import net.explorersfriend.region.RegionChunkExtractor;

import java.nio.file.Path;

/**
 * Everything the render workers need to know about one dimension: where its region
 * files live and how to extract render data from them. Immutable; built once per
 * dimension at startup.
 */
public record DimensionContext(
        String dimensionId,
        String slug,
        Path regionDir,
        RegionChunkExtractor extractor) {
}
