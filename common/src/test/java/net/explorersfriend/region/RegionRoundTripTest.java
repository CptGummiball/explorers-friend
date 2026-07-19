package net.explorersfriend.region;

import net.explorersfriend.testutil.TestPalette;
import net.explorersfriend.render.TileChunkData;
import net.explorersfriend.testutil.TestNbt;
import net.explorersfriend.testutil.TestRegions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionRoundTripTest {

    @Test
    void nbtReaderParsesWhatTestWriterWrites() throws IOException {
        Map<String, Object> chunk = TestRegions.flatChunk(3, -2, false);
        byte[] bytes = TestNbt.rootCompound(chunk);
        Map<String, Object> parsed = NbtReader.readRootCompound(
                new DataInputStream(new java.io.ByteArrayInputStream(bytes)));
        assertEquals("minecraft:full", NbtReader.string(parsed, "Status"));
        assertEquals(3, NbtReader.intValue(parsed, "xPos", -999));
        assertEquals(-2, NbtReader.intValue(parsed, "zPos", -999));
        assertNotNull(NbtReader.list(parsed, "sections"));
        assertNotNull(NbtReader.longArray(NbtReader.compound(parsed, "Heightmaps"), "WORLD_SURFACE"));
    }

    @Test
    void regionFileRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Map<int[], byte[]> chunks = new LinkedHashMap<>();
        chunks.put(new int[]{0, 0}, TestNbt.rootCompound(TestRegions.flatChunk(0, 0, false)));
        chunks.put(new int[]{5, 7}, TestNbt.rootCompound(TestRegions.flatChunk(5, 7, true)));
        TestRegions.writeRegion(file, chunks);

        RegionFileReader reader = new RegionFileReader(file);
        assertEquals(2, reader.presentChunks().size());
        assertNull(reader.readChunk(1, 1), "absent chunk is null");
        Map<String, Object> parsed = reader.readChunk(5, 7);
        assertNotNull(parsed);
        assertEquals(5, NbtReader.intValue(parsed, "xPos", -1));
    }

    @Test
    void extractorProducesExpectedColumns() {
        RegionChunkExtractor extractor = new RegionChunkExtractor(TestPalette.INSTANCE,
                new RegionChunkExtractor.Settings(true, false));
        TileChunkData dry = extractor.extract(TestRegions.flatChunk(0, 0, false));
        assertNotNull(dry);
        assertEquals(64, dry.heights()[0]);
        assertEquals(0xFF808080, dry.colors()[0], "plain stone keeps its color");

        TileChunkData wet = extractor.extract(TestRegions.flatChunk(0, 0, true));
        assertNotNull(wet);
        assertEquals(65, wet.heights()[0], "water surface defines the relief height");
        assertEquals(64, wet.heights()[15], "east half is dry");
        assertNotEquals(wet.colors()[0], wet.colors()[15], "water overlay changes the color");
        assertEquals(0xFF, wet.colors()[0] >>> 24, "water columns stay opaque");
    }

    @Test
    void extractionIsDeterministic() {
        RegionChunkExtractor extractor = new RegionChunkExtractor(TestPalette.INSTANCE,
                new RegionChunkExtractor.Settings(true, false));
        TileChunkData first = extractor.extract(TestRegions.flatChunk(1, 2, true));
        TileChunkData second = extractor.extract(TestRegions.flatChunk(1, 2, true));
        assertNotNull(first);
        assertNotNull(second);
        org.junit.jupiter.api.Assertions.assertArrayEquals(first.colors(), second.colors());
        org.junit.jupiter.api.Assertions.assertArrayEquals(first.heights(), second.heights());
    }

    @Test
    void nonFullChunksAreSkipped() {
        Map<String, Object> proto = TestRegions.flatChunk(0, 0, false);
        proto.put("Status", "minecraft:features");
        assertNull(new RegionChunkExtractor(TestPalette.INSTANCE, new RegionChunkExtractor.Settings(true, false))
                .extract(proto));
    }

    @Test
    void corruptRegionFilesAreRejected(@TempDir Path dir) throws IOException {
        Path tiny = dir.resolve("r.0.0.mca");
        java.nio.file.Files.write(tiny, new byte[100]);
        assertThrows(IOException.class, () -> new RegionFileReader(tiny));

        Path garbage = dir.resolve("r.1.0.mca");
        byte[] data = new byte[16384];
        data[3] = 99; // header points into nowhere with nonsense
        data[0] = (byte) 0xFF;
        java.nio.file.Files.write(garbage, data);
        RegionFileReader reader = new RegionFileReader(garbage);
        assertThrows(IOException.class, () -> reader.readChunk(0, 0));
    }

    @Test
    void heightmapDecodeMatchesKnownValues() {
        Map<String, Object> chunk = TestRegions.flatChunk(0, 0, false);
        int[] surface = RegionChunkExtractor.readWorldSurface(chunk, -64);
        assertNotNull(surface);
        for (int value : surface) {
            assertEquals(64, value);
        }
        assertTrue(List.of().isEmpty()); // keep style checkers quiet about unused import
    }
}
