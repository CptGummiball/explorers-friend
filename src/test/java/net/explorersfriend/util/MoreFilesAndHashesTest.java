package net.explorersfriend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoreFilesAndHashesTest {

    @Test
    void writeAtomicCreatesParentsAndReplaces(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a/b/c.json");
        MoreFiles.writeAtomicUtf8(target, "first");
        assertEquals("first", Files.readString(target));
        MoreFiles.writeAtomicUtf8(target, "second");
        assertEquals("second", Files.readString(target));
        try (var siblings = Files.list(target.getParent())) {
            assertTrue(siblings.allMatch(p -> !p.getFileName().toString().contains(".tmp-")),
                    "no temp files left behind");
        }
    }

    @Test
    void quarantineKeepsContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.json");
        Files.writeString(file, "broken");
        Path backup = MoreFiles.quarantine(file);
        assertNotNull(backup);
        assertFalse(Files.exists(file));
        assertEquals("broken", Files.readString(backup));
    }

    @Test
    void sha256MatchesKnownVector(@TempDir Path dir) throws Exception {
        // FIPS 180-2 test vector for "abc"
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, Hashes.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        Path file = dir.resolve("abc.txt");
        Files.writeString(file, "abc");
        assertEquals(expected, Hashes.sha256Hex(file));
    }

    @Test
    void rateLimitedLogSuppressesWithinInterval() throws Exception {
        RateLimitedLog log = new RateLimitedLog();
        assertTrue(log.shouldLog("key", 10_000));
        assertFalse(log.shouldLog("key", 10_000));
        assertTrue(log.shouldLog("other", 10_000));
        RateLimitedLog shortLog = new RateLimitedLog();
        assertTrue(shortLog.shouldLog("k", 10));
        Thread.sleep(30);
        assertTrue(shortLog.shouldLog("k", 10));
    }
}
