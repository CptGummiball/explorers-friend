package net.explorersfriend.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceSafetyTest {

    private static Path zipWithEntries(Path dir, int count, byte[] content) throws IOException {
        Path file = dir.resolve("test-" + count + ".jar");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("assets/e" + i + ".json"));
                zip.write(content);
                zip.closeEntry();
            }
        }
        Files.write(file, buffer.toByteArray());
        return file;
    }

    @Test
    void readsNormalEntries(@TempDir Path dir) throws IOException {
        Path jar = zipWithEntries(dir, 3, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        try (ZipResourceSource source = new ZipResourceSource(jar, "sha", "test", 1000, 1024)) {
            assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), source.read("assets/e0.json"));
            assertNull(source.read("assets/missing.json"));
        }
    }

    @Test
    void rejectsTooManyEntries(@TempDir Path dir) throws IOException {
        Path jar = zipWithEntries(dir, 50, new byte[]{1});
        assertThrows(IOException.class, () -> new ZipResourceSource(jar, "sha", "test", 10, 1024));
    }

    @Test
    void rejectsOversizedEntries(@TempDir Path dir) throws IOException {
        Path jar = zipWithEntries(dir, 1, new byte[5000]);
        try (ZipResourceSource source = new ZipResourceSource(jar, "sha", "test", 1000, 1024)) {
            assertThrows(IOException.class, () -> source.read("assets/e0.json"));
        }
    }

    @Test
    void rejectsSuspiciousPaths(@TempDir Path dir) throws IOException {
        Path jar = zipWithEntries(dir, 1, new byte[]{1});
        try (ZipResourceSource source = new ZipResourceSource(jar, "sha", "test", 1000, 1024)) {
            assertNull(source.read("../outside.txt"));
            assertNull(source.read("/absolute.txt"));
            assertNull(source.read("a\\b.txt"));
        }
    }

    @Test
    void directorySourceStaysInsideRoot(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("modroot");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/ok.json"), "{}");
        Files.writeString(dir.resolve("outside.txt"), "secret");
        DirectoryResourceSource source = new DirectoryResourceSource(root, "sha", "test", 1024);
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), source.read("assets/ok.json"));
        assertNull(source.read("../outside.txt"));
        assertNull(source.read("/outside.txt"));
    }

    @Test
    void resourcePathHelpers() {
        assertEquals("minecraft:stone", ResourcePaths.normalizeId("stone"));
        assertEquals("mod:block/x", ResourcePaths.normalizeId("MOD:block/x"));
        assertEquals("assets/minecraft/blockstates/stone.json", ResourcePaths.blockstatePath("stone"));
        assertEquals("assets/mod/models/block/thing.json", ResourcePaths.modelPath("mod:block/thing"));
        assertEquals("assets/mod/textures/block/thing.png", ResourcePaths.texturePath("mod:block/thing"));
        assertTrue(ResourcePaths.isValidId("mod:some/block_1.x"));
        assertFalse(ResourcePaths.isValidId("mod:../escape"));
        assertFalse(ResourcePaths.isValidId("bad namespace:x"));
    }
}
