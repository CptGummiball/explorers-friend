package net.explorersfriend.cache;

import net.explorersfriend.color.BlockColorCache;
import net.explorersfriend.color.BlockColorResult;
import net.explorersfriend.color.TextureColorCache;
import net.explorersfriend.color.TintType;
import net.explorersfriend.scan.InventoryCache;
import net.explorersfriend.scan.JarRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheVersioningTest {

    @Test
    void textureCacheRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("texture-colors.json");
        TextureColorCache cache = new TextureColorCache();
        cache.put("aabb", 0xFF123456);
        cache.save(file, 1, "first_frame");

        TextureColorCache loaded = TextureColorCache.load(file, 1, "first_frame");
        assertEquals(0xFF123456, loaded.get("aabb"));
        assertEquals(1, loaded.size());
    }

    @Test
    void textureCacheDiscardsOnAlgorithmChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("texture-colors.json");
        TextureColorCache cache = new TextureColorCache();
        cache.put("aabb", 1);
        cache.save(file, 1, "first_frame");
        assertEquals(0, TextureColorCache.load(file, 2, "first_frame").size(), "algorithm bump invalidates");
        assertEquals(0, TextureColorCache.load(file, 1, "average").size(), "animation mode change invalidates");
    }

    @Test
    void textureCacheQuarantinesCorruptFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("texture-colors.json");
        Files.writeString(file, "{broken");
        TextureColorCache loaded = TextureColorCache.load(file, 1, "first_frame");
        assertEquals(0, loaded.size());
        assertTrue(Files.exists(dir.resolve("texture-colors.json.corrupt-1")));
    }

    @Test
    void blockColorCacheValidOnlyForExactContext(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("block-colors.json");
        Map<String, BlockColorResult> colors = Map.of("minecraft:stone",
                new BlockColorResult("minecraft:stone", 0xFF7A7A7A, TintType.NONE,
                        "sha1", "minecraft:block/stone", "minecraft:block/stone", null, 1, 42L));
        BlockColorCache.save(file, "jarset-A", 1, "first_frame", colors);

        Optional<Map<String, BlockColorResult>> hit =
                BlockColorCache.loadIfValid(file, "jarset-A", 1, "first_frame");
        assertTrue(hit.isPresent());
        assertEquals(0xFF7A7A7A, hit.get().get("minecraft:stone").argb());

        assertTrue(BlockColorCache.loadIfValid(file, "jarset-B", 1, "first_frame").isEmpty(),
                "changed mod set invalidates");
        assertTrue(BlockColorCache.loadIfValid(file, "jarset-A", 2, "first_frame").isEmpty(),
                "changed algorithm invalidates");
        assertTrue(BlockColorCache.loadIfValid(file, "jarset-A", 1, "average").isEmpty(),
                "changed animation mode invalidates");
    }

    @Test
    void inventoryCacheRoundTripAndCorruption(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("jar-inventory.json");
        JarRecord record = new JarRecord("C:/mods/foo.jar", "foo", "1.2.3", "foo.jar", "", 123, 456, "sha256hex");
        InventoryCache.save(file, List.of(record));
        Map<String, JarRecord> loaded = InventoryCache.load(file);
        assertEquals(record, loaded.get("C:/mods/foo.jar"));

        Files.writeString(file, "###");
        assertTrue(InventoryCache.load(file).isEmpty());
        assertTrue(Files.exists(dir.resolve("jar-inventory.json.corrupt-1")));
        assertNull(InventoryCache.load(dir.resolve("missing.json")).get("x"));
    }
}
