package net.explorersfriend.scan;

/**
 * Identity of one scanned mod JAR (or nested JAR). {@code locationKey} uniquely names
 * the physical location ({@code <absolute path>} or {@code <parent path>!<sub path>});
 * {@code sha256} is the authoritative content identity used for change detection and
 * deduplication. {@code size}/{@code mtime} only serve as a fast path to avoid
 * re-hashing files that are provably untouched.
 */
public record JarRecord(
        String locationKey,
        String modId,
        String version,
        String fileName,
        String nestedIn,
        long size,
        long mtime,
        String sha256) {
}
