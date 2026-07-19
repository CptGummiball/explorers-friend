package net.explorersfriend.resource;

import net.explorersfriend.ExplorersFriend;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Ordered view over every open {@link ResourceSource}: mod JARs first (sorted by mod id
 * for determinism), the vanilla asset source last. The first source containing a path
 * wins. Thread-safe because the source list is immutable and sources are thread-safe.
 */
public final class ResourcePool implements Closeable {

    /** A successful lookup, remembering which source provided the bytes. */
    public record Found(byte[] data, ResourceSource source) {
    }

    private final List<ResourceSource> sources;

    public ResourcePool(List<ResourceSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public Found find(String path) {
        for (ResourceSource source : sources) {
            try {
                byte[] data = source.read(path);
                if (data != null) {
                    return new Found(data, source);
                }
            } catch (IOException e) {
                ExplorersFriend.LOGGER.debug("[ExplorersFriend/Scanner] {} while reading {} from {}",
                        e.getMessage(), path, source.describe());
            }
        }
        return null;
    }

    public byte[] read(String path) {
        Found found = find(path);
        return found == null ? null : found.data();
    }

    public int sourceCount() {
        return sources.size();
    }

    @Override
    public void close() {
        for (ResourceSource source : sources) {
            try {
                source.close();
            } catch (IOException e) {
                ExplorersFriend.LOGGER.debug("[ExplorersFriend/Scanner] Failed to close {}: {}",
                        source.describe(), e.getMessage());
            }
        }
    }
}
