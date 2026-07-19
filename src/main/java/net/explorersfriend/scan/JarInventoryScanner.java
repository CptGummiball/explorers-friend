package net.explorersfriend.scan;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.util.Hashes;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Builds the per-start inventory of every mod JAR known to Fabric Loader, including
 * nested (jar-in-jar) files, and diffs it against the persisted cache.
 *
 * <p>Content identity is SHA-256. Unchanged (path, size, mtime) triples reuse the cached
 * hash without re-reading the file; everything else is (re-)hashed on the scan pool.
 * Development-environment mods that are plain directories get a deterministic digest of
 * their file listing instead.</p>
 */
public final class JarInventoryScanner {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    /** Outcome of one inventory run, including the diff against the previous start. */
    public record Result(
            List<JarRecord> records,
            int unchanged,
            int added,
            int changed,
            int removed,
            int duplicateContents,
            String jarSetHash) {

        public int totalJars() {
            return records.size();
        }
    }

    private record PhysicalLocation(Path topLevel, List<String> nestedChain) {
        String key() {
            return nestedChain.isEmpty()
                    ? topLevel.toAbsolutePath().toString()
                    : topLevel.toAbsolutePath() + "!" + String.join("!", nestedChain);
        }
    }

    private JarInventoryScanner() {
    }

    public static Result scan(Map<String, JarRecord> cached, ExecutorService hashPool) {
        Map<String, ModContainer> byId = new HashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            byId.put(mod.getMetadata().getId(), mod);
        }

        record Pending(ModContainer mod, PhysicalLocation location) {
        }
        List<Pending> pending = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            PhysicalLocation location = resolve(mod, byId, 0);
            if (location == null) {
                LOGGER.debug("[ExplorersFriend/Scanner] Mod '{}' has no resolvable file origin ({}); skipped",
                        mod.getMetadata().getId(), mod.getOrigin().getKind());
                continue;
            }
            pending.add(new Pending(mod, location));
        }

        List<Future<JarRecord>> futures = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            futures.add(hashPool.submit(toRecordTask(p.mod(), p.location(), cached)));
        }
        List<JarRecord> records = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                records.add(futures.get(i).get());
            } catch (Exception e) {
                Pending p = pending.get(i);
                LOGGER.warn("[ExplorersFriend/Scanner] Could not inventory '{}' from {}: {}",
                        p.mod().getMetadata().getId(), p.location().key(), rootMessage(e));
            }
        }
        records.sort(Comparator.comparing(JarRecord::locationKey));

        int unchanged = 0;
        int added = 0;
        int changed = 0;
        int duplicates = 0;
        Map<String, JarRecord> cachedByHash = new HashMap<>();
        for (JarRecord old : cached.values()) {
            cachedByHash.putIfAbsent(old.sha256(), old);
        }
        Map<String, JarRecord> currentKeys = new HashMap<>();
        for (JarRecord record : records) {
            currentKeys.put(record.locationKey(), record);
            JarRecord old = cached.get(record.locationKey());
            if (old != null) {
                if (old.sha256().equals(record.sha256())) {
                    unchanged++;
                } else {
                    changed++;
                }
            } else if (cachedByHash.containsKey(record.sha256())) {
                duplicates++; // same content under a new name/location: no re-analysis needed
                unchanged++;
            } else {
                added++;
            }
        }
        int removed = 0;
        for (String oldKey : cached.keySet()) {
            if (!currentKeys.containsKey(oldKey)) {
                removed++;
            }
        }

        TreeSet<String> hashes = new TreeSet<>();
        for (JarRecord record : records) {
            hashes.add(record.sha256());
        }
        String jarSetHash = Hashes.sha256Hex(String.join("\n", hashes).getBytes(StandardCharsets.UTF_8));

        return new Result(List.copyOf(records), unchanged, added, changed, removed, duplicates, jarSetHash);
    }

    private static Callable<JarRecord> toRecordTask(ModContainer mod, PhysicalLocation location,
                                                    Map<String, JarRecord> cached) {
        return () -> {
            String key = location.key();
            String modId = mod.getMetadata().getId();
            String version = mod.getMetadata().getVersion().getFriendlyString();
            Path top = location.topLevel();
            String fileName = top.getFileName() == null ? top.toString() : top.getFileName().toString();
            String nestedIn = location.nestedChain().isEmpty()
                    ? ""
                    : fileName + "!" + String.join("!", location.nestedChain());
            if (!location.nestedChain().isEmpty()) {
                fileName = location.nestedChain().get(location.nestedChain().size() - 1);
            }

            if (Files.isDirectory(top)) {
                String digest = directoryDigest(top);
                return new JarRecord(key, modId, version, fileName, nestedIn, 0, 0, digest);
            }

            long size = Files.size(top);
            long mtime = Files.getLastModifiedTime(top).toMillis();
            JarRecord old = cached.get(key);
            if (old != null && old.size() == size && old.mtime() == mtime
                    && !location.nestedChain().isEmpty() == !old.nestedIn().isEmpty()) {
                // Fast path: physically untouched file, trust the cached content hash.
                return new JarRecord(key, modId, version, fileName, nestedIn, size, mtime, old.sha256());
            }
            String sha = location.nestedChain().isEmpty()
                    ? Hashes.sha256Hex(top)
                    : hashNestedEntry(top, location.nestedChain());
            return new JarRecord(key, modId, version, fileName, nestedIn, size, mtime, sha);
        };
    }

    private static PhysicalLocation resolve(ModContainer mod, Map<String, ModContainer> byId, int depth) {
        if (depth > 8) {
            return null; // defensive: absurd nesting
        }
        ModOrigin origin = mod.getOrigin();
        switch (origin.getKind()) {
            case PATH -> {
                List<Path> paths = origin.getPaths();
                if (paths.isEmpty()) {
                    return null;
                }
                return new PhysicalLocation(paths.get(0), List.of());
            }
            case NESTED -> {
                ModContainer parent = byId.get(origin.getParentModId());
                if (parent == null) {
                    return null;
                }
                PhysicalLocation parentLocation = resolve(parent, byId, depth + 1);
                if (parentLocation == null) {
                    return null;
                }
                List<String> chain = new ArrayList<>(parentLocation.nestedChain());
                chain.add(origin.getParentSubLocation());
                return new PhysicalLocation(parentLocation.topLevel(), List.copyOf(chain));
            }
            default -> {
                return null;
            }
        }
    }

    /** SHA-256 of a nested jar entry, following a chain of jar-in-jar locations. */
    static String hashNestedEntry(Path topLevel, List<String> chain) throws IOException {
        try (ZipFile zip = new ZipFile(topLevel.toFile())) {
            ZipEntry first = zip.getEntry(chain.get(0));
            if (first == null) {
                throw new FileNotFoundException(chain.get(0) + " in " + topLevel);
            }
            try (InputStream firstStream = zip.getInputStream(first)) {
                InputStream current = firstStream;
                List<ZipInputStream> opened = new ArrayList<>();
                try {
                    for (int i = 1; i < chain.size(); i++) {
                        ZipInputStream zin = new ZipInputStream(current);
                        opened.add(zin);
                        ZipEntry entry;
                        boolean found = false;
                        while ((entry = zin.getNextEntry()) != null) {
                            if (entry.getName().equals(chain.get(i))) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            throw new FileNotFoundException(chain.get(i) + " in " + topLevel + "!" + chain.get(i - 1));
                        }
                        current = zin;
                    }
                    return Hashes.sha256Hex(current);
                } finally {
                    for (ZipInputStream zin : opened) {
                        try {
                            zin.close();
                        } catch (IOException ignored) {
                            // stream chain shares the underlying stream; best effort
                        }
                    }
                }
            }
        }
    }

    /** Deterministic digest for directory-based dev-environment "jars". */
    static String directoryDigest(Path dir) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<String> lines = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    lines.add(dir.relativize(p) + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    lines.add(dir.relativize(p) + "|?|?");
                }
            });
        }
        lines.sort(Comparator.naturalOrder());
        for (String line : lines) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return "dir-" + HexFormat.of().formatHex(digest.digest());
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
