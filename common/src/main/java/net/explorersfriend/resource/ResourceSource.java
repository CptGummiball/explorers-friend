package net.explorersfriend.resource;

import java.io.Closeable;
import java.io.IOException;

/**
 * One provider of read-only assets (a mod JAR, the vanilla client JAR, or a development
 * directory). Implementations must be safe for concurrent {@link #read} calls from the
 * scan workers.
 */
public interface ResourceSource extends Closeable {

    /**
     * @param path full asset path, e.g. {@code assets/minecraft/models/block/stone.json}
     * @return the file bytes, or {@code null} when this source does not contain the path
     * @throws IOException on real IO failures (not on absence) or when safety limits
     *                     (decompressed size) are exceeded
     */
    byte[] read(String path) throws IOException;

    /** Stable identity for cache metadata, normally the SHA-256 of the backing JAR. */
    String sourceId();

    /** Human-readable origin ("mod 'foo' (foo-1.2.jar)") for logs. */
    String describe();
}
