package net.explorersfriend.render;

import net.explorersfriend.region.RegionChunkExtractor;
import net.explorersfriend.testutil.TestPalette;
import net.explorersfriend.testutil.TestRegions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDeterminismTest {

    private static List<TileChunkData> sampleChunks() {
        RegionChunkExtractor extractor = new RegionChunkExtractor(TestPalette.INSTANCE,
                new RegionChunkExtractor.Settings(true, false));
        List<TileChunkData> chunks = new ArrayList<>();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                chunks.add(extractor.extract(TestRegions.flatChunk(x, z, (x + z) % 2 == 0)));
            }
        }
        return chunks;
    }

    @Test
    void sameInputRendersIdenticalTiles() {
        TileRenderer renderer = new TileRenderer(true);
        int[] first = new int[512 * 512];
        int[] second = new int[512 * 512];
        renderer.renderRegion(sampleChunks(), 0, 0, first);
        renderer.renderRegion(sampleChunks(), 0, 0, second);
        assertArrayEquals(first, second);
    }

    @Test
    void patchOnlyTouchesTheChunkArea() {
        TileRenderer renderer = new TileRenderer(true);
        int[] pixels = new int[512 * 512];
        java.util.Arrays.fill(pixels, 0xFF101010);
        TileChunkData chunk = sampleChunks().get(3); // chunk (1,1) → pixels 16..31 in both axes
        renderer.patchChunk(pixels, chunk, 0, 0);
        assertEquals(0xFF101010, pixels[0], "outside area untouched");
        assertEquals(0xFF101010, pixels[15 + 512 * 15], "outside area untouched");
        assertNotEquals(0xFF101010, pixels[16 + 512 * 16], "chunk area repainted");
        assertEquals(0xFF101010, pixels[40 + 512 * 40], "beyond the chunk untouched");
    }

    @Test
    void reliefShadingRespondsToSlopes() {
        int flat = TileRenderer.shadeByRelief(0xFF808080, 64, 64, 64);
        int uphill = TileRenderer.shadeByRelief(0xFF808080, 70, 64, 64);
        int downhill = TileRenderer.shadeByRelief(0xFF808080, 60, 64, 64);
        assertEquals(0xFF808080, flat);
        assertTrue((uphill & 0xFF) > 0x80, "slope up brightens");
        assertTrue((downhill & 0xFF) < 0x80, "slope down darkens");
    }

    @Test
    void pyramidAverageHandlesTransparency() {
        assertEquals(0, TilePyramid.average(0, 0, 0, 0));
        assertEquals(0xFF808080, TilePyramid.average(0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080));
        int mixed = TilePyramid.average(0xFF000000 | 100 << 16, 0, 0, 0);
        assertEquals(100, (mixed >> 16) & 0xFF, "transparent neighbours don't dilute");
    }

    @Test
    void downsamplePlacesQuadrantsCorrectly() {
        int[] child = new int[512 * 512];
        java.util.Arrays.fill(child, 0xFF336699);
        int[] parent = new int[512 * 512];
        TilePyramid.downsampleInto(child, parent, 1, 0);
        assertEquals(0, parent[0], "left half untouched");
        assertEquals(0xFF336699, parent[300], "right quadrant filled");
        assertEquals(0, parent[512 * 300 + 300], "bottom half untouched");
    }

    @Test
    void tileKeyParentMathWorksForNegatives() {
        assertEquals(new TileKey("d", 1, 0, 0), new TileKey("d", 0, 1, 1).parent());
        assertEquals(new TileKey("d", 1, -1, -1), new TileKey("d", 0, -1, -1).parent());
        assertEquals(new TileKey("d", 1, -1, -1), new TileKey("d", 0, -2, -2).parent());
        assertEquals(new TileKey("d", 0, -1, -1), TileKey.baseForChunk("d", -1, -1));
        assertEquals(new TileKey("d", 0, 0, 0), TileKey.baseForChunk("d", 31, 31));
    }
}
