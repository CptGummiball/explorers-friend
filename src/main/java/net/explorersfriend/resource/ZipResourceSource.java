package net.explorersfriend.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Assets from a JAR/ZIP file. Defensive against hostile archives:
 * <ul>
 *   <li>refuses archives with more entries than the configured limit (zip bombs)</li>
 *   <li>caps the decompressed size of every read entry</li>
 *   <li>only serves normalized paths that were explicitly requested (no {@code ..},
 *       no absolute paths, no wildcard listing)</li>
 * </ul>
 * {@link ZipFile} is internally thread-safe for concurrent {@code getInputStream} calls.
 */
public final class ZipResourceSource implements ResourceSource {

    private final ZipFile zip;
    private final String sourceId;
    private final String description;
    private final long maxEntryBytes;

    /** @throws IOException also when the archive exceeds {@code maxZipEntries}. */
    public ZipResourceSource(Path file, String sourceId, String description,
                             int maxZipEntries, long maxEntryBytes) throws IOException {
        this.zip = new ZipFile(file.toFile());
        this.sourceId = sourceId;
        this.description = description;
        this.maxEntryBytes = maxEntryBytes;
        if (zip.size() > maxZipEntries) {
            int size = zip.size();
            zip.close();
            throw new IOException("archive has " + size + " entries (limit " + maxZipEntries + ")");
        }
    }

    @Override
    public byte[] read(String path) throws IOException {
        if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            return null;
        }
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        long declared = entry.getSize();
        if (declared > maxEntryBytes) {
            throw new IOException(path + " declares " + declared + " bytes (limit " + maxEntryBytes + ")");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] data = in.readNBytes((int) Math.min(maxEntryBytes + 1, Integer.MAX_VALUE - 8));
            if (data.length > maxEntryBytes) {
                throw new IOException(path + " decompresses beyond the " + maxEntryBytes + " byte limit");
            }
            return data;
        }
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
