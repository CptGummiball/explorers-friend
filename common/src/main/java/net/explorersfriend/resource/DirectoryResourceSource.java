package net.explorersfriend.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Assets from a plain directory (development environments, where mod "JARs" are
 * unpacked class/resource folders). Rejects path escapes and does not follow
 * symbolic links out of the root.
 */
public final class DirectoryResourceSource implements ResourceSource {

    private final Path root;
    private final String sourceId;
    private final String description;
    private final long maxEntryBytes;

    public DirectoryResourceSource(Path root, String sourceId, String description, long maxEntryBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.sourceId = sourceId;
        this.description = description;
        this.maxEntryBytes = maxEntryBytes;
    }

    @Override
    public byte[] read(String path) throws IOException {
        if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            return null;
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        long size = Files.size(resolved);
        if (size > maxEntryBytes) {
            throw new IOException(path + " is " + size + " bytes (limit " + maxEntryBytes + ")");
        }
        return Files.readAllBytes(resolved);
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
    public void close() {
        // nothing to release
    }
}
