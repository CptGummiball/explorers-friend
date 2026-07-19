package net.explorersfriend.region;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only parser for Anvil region files ({@code r.X.Z.mca}) — an original
 * implementation from the publicly documented format: an 8 KiB header (1024 chunk
 * locations + 1024 timestamps) followed by 4 KiB sectors holding length-prefixed,
 * compressed chunk NBT.
 *
 * <p>The whole file is read into memory once (regions are typically a few MiB; a hard
 * cap rejects absurd files), so parsing needs no further IO and never touches live
 * server state — safe on any worker thread.</p>
 */
public final class RegionFileReader {

    /** Regions beyond this size are considered corrupt/hostile and skipped. */
    private static final long MAX_REGION_FILE_BYTES = 256L * 1024 * 1024;
    private static final int SECTOR_BYTES = 4096;

    private final byte[] data;
    private final Path file;

    public RegionFileReader(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 2L * SECTOR_BYTES) {
            throw new IOException(file.getFileName() + " is too small to be a region file (" + size + " bytes)");
        }
        if (size > MAX_REGION_FILE_BYTES) {
            throw new IOException(file.getFileName() + " exceeds the region size limit (" + size + " bytes)");
        }
        this.data = Files.readAllBytes(file);
        this.file = file;
    }

    /** Local chunk coordinates (0..31) that are present in this region. */
    public List<int[]> presentChunks() {
        List<int[]> present = new ArrayList<>();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (locationOf(x, z) != 0) {
                    present.add(new int[]{x, z});
                }
            }
        }
        return present;
    }

    /**
     * Parses the chunk at local coordinates (0..31). Returns {@code null} when the
     * chunk is absent. Throws on corrupt data — callers isolate per chunk.
     */
    public Map<String, Object> readChunk(int localX, int localZ) throws IOException {
        int location = locationOf(localX, localZ);
        if (location == 0) {
            return null;
        }
        int sectorOffset = location >>> 8;
        int sectorCount = location & 0xFF;
        long byteOffset = (long) sectorOffset * SECTOR_BYTES;
        if (sectorOffset < 2 || byteOffset + 5 > data.length) {
            throw new IOException("chunk (" + localX + "," + localZ + ") points outside the file");
        }
        int start = (int) byteOffset;
        int declaredLength = ((data[start] & 0xFF) << 24) | ((data[start + 1] & 0xFF) << 16)
                | ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
        if (declaredLength <= 0 || start + 4 + declaredLength > data.length
                || declaredLength > (sectorCount + 1) * SECTOR_BYTES) {
            throw new IOException("chunk (" + localX + "," + localZ + ") has invalid length " + declaredLength);
        }
        int compressionType = data[start + 4] & 0xFF;
        InputStream raw = new ByteArrayInputStream(data, start + 5, declaredLength - 1);
        InputStream decompressed = switch (compressionType) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("chunk (" + localX + "," + localZ + ") uses unsupported compression "
                    + compressionType + " (enable zlib on the server or report this)");
        };
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(decompressed, 16 * 1024))) {
            return NbtReader.readRootCompound(in);
        }
    }

    /** Header entry: 3-byte sector offset and 1-byte sector count, packed as (offset<<8 | count). */
    private int locationOf(int localX, int localZ) {
        int index = (localZ * 32 + localX) * 4;
        int sectorOffset = ((data[index] & 0xFF) << 16) | ((data[index + 1] & 0xFF) << 8) | (data[index + 2] & 0xFF);
        int sectorCount = data[index + 3] & 0xFF;
        return (sectorOffset << 8) | sectorCount;
    }

    public Path file() {
        return file;
    }
}
