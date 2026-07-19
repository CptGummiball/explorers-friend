package net.explorersfriend.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * File helpers used by every cache writer in the mod.
 *
 * <p>All cache and tile writes go through {@link #writeAtomic(Path, byte[])}: the data
 * is written to a temporary sibling file first and then moved over the target, so a
 * crash or full disk can never leave a half-written file behind. Thread-safe; callers
 * coordinate higher-level exclusivity themselves.</p>
 */
public final class MoreFiles {

    private MoreFiles() {
    }

    /** Creates the parent directory of {@code file} if it is missing. */
    public static void ensureParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Atomically replaces {@code target} with {@code data}. Falls back to a plain
     * replace move on file systems without atomic-move support.
     */
    public static void writeAtomic(Path target, byte[] data) throws IOException {
        ensureParent(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36));
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void writeAtomicUtf8(Path target, String content) throws IOException {
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Best-effort recursive deletion; never throws, returns number of deleted files. */
    public static int deleteRecursivelyQuiet(Path root) {
        if (!Files.exists(root)) {
            return 0;
        }
        int[] count = {0};
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    if (Files.deleteIfExists(p) && Files.isRegularFile(p)) {
                        count[0]++;
                    }
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
        return count[0];
    }

    /**
     * Moves a corrupt file aside as {@code <name>.corrupt-<n>} so it can be inspected
     * instead of silently deleted. Returns the backup path or {@code null} on failure.
     */
    public static Path quarantine(Path file) {
        for (int i = 1; i <= 100; i++) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + i);
            if (Files.exists(backup)) {
                continue;
            }
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                return backup;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
}
