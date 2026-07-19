package net.explorersfriend.marker;

import net.explorersfriend.config.MapConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomIconStoreTest {

    private static final MapConfig.CustomIcons DEFAULTS = new MapConfig.CustomIcons(true, 64, 128, 256);

    @AfterEach
    void resetRegistry() {
        IconLibrary.setCustomIcons(java.util.Set.of());
    }

    private static void writePng(Path file, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF336699);
        ImageIO.write(img, "png", file.toFile());
    }

    @Test
    void validPngIsLoadedReencodedAndRegistered(@TempDir Path dir) throws IOException {
        writePng(dir.resolve("base_camp.png"), 16, 16);
        CustomIconStore store = new CustomIconStore(dir);
        store.reload(DEFAULTS);

        CustomIconStore.Entry entry = store.get("base_camp");
        assertNotNull(entry);
        assertEquals(64, entry.sha256().length());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(entry.png()));
        assertNotNull(decoded, "served bytes must be a decodable PNG");
        assertEquals(16, decoded.getWidth());
        assertTrue(IconLibrary.isKnown("custom:base_camp"));
        assertTrue(IconLibrary.allIcons().contains("custom:base_camp"));
    }

    @Test
    void invalidNamesAndTraversalAttemptsAreRejected(@TempDir Path dir) throws IOException {
        writePng(dir.resolve("UpperCase.png"), 8, 8);
        writePng(dir.resolve("sp ace.png"), 8, 8);
        writePng(dir.resolve("..evil.png"), 8, 8);
        CustomIconStore store = new CustomIconStore(dir);
        store.reload(DEFAULTS);
        assertTrue(store.names().isEmpty());
        assertFalse(IconLibrary.isKnown("custom:..evil"));
        assertNull(store.get("../../etc/passwd"));
    }

    @Test
    void corruptAndOversizedFilesAreSkippedWithoutFailing(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("broken.png"), new byte[]{1, 2, 3, 4});
        writePng(dir.resolve("too_big.png"), 300, 300);
        writePng(dir.resolve("fine.png"), 32, 32);
        CustomIconStore store = new CustomIconStore(dir);
        store.reload(DEFAULTS);
        assertEquals(java.util.Set.of("fine"), store.names());
    }

    @Test
    void countLimitKeepsAlphabeticalPrefix(@TempDir Path dir) throws IOException {
        writePng(dir.resolve("aaa.png"), 8, 8);
        writePng(dir.resolve("bbb.png"), 8, 8);
        writePng(dir.resolve("ccc.png"), 8, 8);
        CustomIconStore store = new CustomIconStore(dir);
        store.reload(new MapConfig.CustomIcons(true, 2, 128, 256));
        assertEquals(java.util.Set.of("aaa", "bbb"), store.names());
    }

    @Test
    void disabledConfigClearsEverything(@TempDir Path dir) throws IOException {
        writePng(dir.resolve("gone.png"), 8, 8);
        CustomIconStore store = new CustomIconStore(dir);
        store.reload(DEFAULTS);
        assertTrue(IconLibrary.isKnown("custom:gone"));
        store.reload(new MapConfig.CustomIcons(false, 64, 128, 256));
        assertTrue(store.names().isEmpty());
        assertFalse(IconLibrary.isKnown("custom:gone"));
    }

    @Test
    void missingDirectoryIsFine(@TempDir Path dir) {
        CustomIconStore store = new CustomIconStore(dir.resolve("does-not-exist"));
        store.reload(DEFAULTS);
        assertTrue(store.names().isEmpty());
    }
}
