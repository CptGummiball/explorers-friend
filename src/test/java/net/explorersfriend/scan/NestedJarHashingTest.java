package net.explorersfriend.scan;

import net.explorersfriend.util.Hashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NestedJarHashingTest {

    private static byte[] zipWith(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    @Test
    void hashesSingleNestedJar(@TempDir Path dir) throws Exception {
        byte[] innerJar = zipWith("data.txt", "hello".getBytes(StandardCharsets.UTF_8));
        byte[] outerJar = zipWith("META-INF/jars/inner.jar", innerJar);
        Path outer = dir.resolve("outer.jar");
        Files.write(outer, outerJar);

        String nestedHash = JarInventoryScanner.hashNestedEntry(outer, List.of("META-INF/jars/inner.jar"));
        assertEquals(Hashes.sha256Hex(innerJar), nestedHash, "nested hash must equal the inner jar bytes' hash");
    }

    @Test
    void hashesDoublyNestedJar(@TempDir Path dir) throws Exception {
        byte[] innermost = zipWith("x.txt", "deep".getBytes(StandardCharsets.UTF_8));
        byte[] middle = zipWith("jars/deepest.jar", innermost);
        byte[] outer = zipWith("META-INF/jars/middle.jar", middle);
        Path file = dir.resolve("outer.jar");
        Files.write(file, outer);

        String hash = JarInventoryScanner.hashNestedEntry(file,
                List.of("META-INF/jars/middle.jar", "jars/deepest.jar"));
        assertEquals(Hashes.sha256Hex(innermost), hash);
    }

    @Test
    void missingNestedEntryThrows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("outer.jar");
        Files.write(file, zipWith("something.txt", new byte[]{1}));
        assertThrows(FileNotFoundException.class,
                () -> JarInventoryScanner.hashNestedEntry(file, List.of("META-INF/jars/none.jar")));
    }

    @Test
    void directoryDigestIsDeterministicAndContentSensitive(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("devmod");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/a.json"), "{}");
        String first = JarInventoryScanner.directoryDigest(root);
        assertEquals(first, JarInventoryScanner.directoryDigest(root));
        Files.writeString(root.resolve("assets/b.json"), "{}");
        assertNotEquals(first, JarInventoryScanner.directoryDigest(root));
    }
}
