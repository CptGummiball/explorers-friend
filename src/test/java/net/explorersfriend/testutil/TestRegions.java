package net.explorersfriend.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Builds synthetic Anvil region files and 1.18+ chunk NBT for the render tests. */
public final class TestRegions {

    private TestRegions() {
    }

    /**
     * A flat test chunk: stone at {@code groundY} everywhere, plus water columns
     * ({@code waterWestHalf}: x&lt;8 get one water block on top of the stone).
     */
    public static Map<String, Object> flatChunk(int chunkX, int chunkZ, boolean waterWestHalf) {
        int sectionY = 4;              // covers y=64..79
        int groundLocalY = 0;          // y=64
        List<Object> palette = new ArrayList<>();
        palette.add(Map.of("Name", "minecraft:air"));
        palette.add(Map.of("Name", "minecraft:stone"));
        palette.add(Map.of("Name", "minecraft:water"));

        int bits = 4;
        int entriesPerLong = 64 / bits;
        long[] data = new long[4096 / entriesPerLong];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int value = 0;
                    if (y == groundLocalY) {
                        value = 1;
                    } else if (y == groundLocalY + 1 && waterWestHalf && x < 8) {
                        value = 2;
                    }
                    int index = (y * 16 + z) * 16 + x;
                    data[index / entriesPerLong] |= (long) value << ((index % entriesPerLong) * bits);
                }
            }
        }

        // WORLD_SURFACE: stored value = topY - minY + 1; minY = -64
        long[] heightmap = new long[37];
        int hmBits = 9;
        int hmPerLong = 64 / hmBits;
        for (int i = 0; i < 256; i++) {
            int x = i % 16;
            int topY = (waterWestHalf && x < 8) ? 65 : 64;
            long value = topY - (-64) + 1;
            heightmap[i / hmPerLong] |= value << ((i % hmPerLong) * hmBits);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("Y", (byte) sectionY);
        section.put("block_states", Map.of("palette", palette, "data", data));
        section.put("biomes", Map.of("palette", List.of("minecraft:plains")));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Status", "minecraft:full");
        root.put("xPos", chunkX);
        root.put("zPos", chunkZ);
        root.put("yPos", -4);
        root.put("sections", List.of(section));
        root.put("Heightmaps", new HashMap<>(Map.of("WORLD_SURFACE", heightmap)));
        return root;
    }

    /** Writes a region file containing the given chunks at their local coordinates. */
    public static void writeRegion(Path file, Map<int[], byte[]> chunkNbtByLocalPos) {
        try {
            List<byte[]> payloads = new ArrayList<>();
            int[] locations = new int[1024];
            int nextSector = 2;
            for (Map.Entry<int[], byte[]> entry : chunkNbtByLocalPos.entrySet()) {
                byte[] compressed = deflate(entry.getValue());
                ByteArrayOutputStream chunkOut = new ByteArrayOutputStream();
                int length = compressed.length + 1;
                chunkOut.write((length >>> 24) & 0xFF);
                chunkOut.write((length >>> 16) & 0xFF);
                chunkOut.write((length >>> 8) & 0xFF);
                chunkOut.write(length & 0xFF);
                chunkOut.write(2); // zlib
                chunkOut.write(compressed);
                byte[] payload = chunkOut.toByteArray();
                int sectors = (payload.length + 4095) / 4096;
                int x = entry.getKey()[0];
                int z = entry.getKey()[1];
                locations[z * 32 + x] = (nextSector << 8) | sectors;
                payloads.add(java.util.Arrays.copyOf(payload, sectors * 4096));
                nextSector += sectors;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int location : locations) {
                out.write((location >>> 24) & 0xFF);
                out.write((location >>> 16) & 0xFF);
                out.write((location >>> 8) & 0xFF);
                out.write(location & 0xFF);
            }
            out.write(new byte[4096]); // timestamps
            for (byte[] payload : payloads) {
                out.write(payload);
            }
            Files.write(file, out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(buffer, new Deflater(6))) {
            deflater.write(data);
        }
        return buffer.toByteArray();
    }
}
