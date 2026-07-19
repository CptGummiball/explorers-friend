package net.explorersfriend.config;

import com.google.gson.JsonObject;
import net.explorersfriend.util.Jsonc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigIOTest {

    @Test
    void defaultsSurviveEmptyObject() {
        List<String> warnings = new ArrayList<>();
        MapConfig config = ConfigIO.read(new JsonObject(), MapConfig.defaults(), warnings);
        assertEquals(MapConfig.defaults(), config);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidValuesFallBackWithWarnings() {
        JsonObject root = Jsonc.parse("""
                {
                  "web": {"port": 99999, "threads": "many"},
                  "render": {"workers": -3},
                  "players": {"show": "yes"}
                }
                """).getAsJsonObject();
        List<String> warnings = new ArrayList<>();
        MapConfig config = ConfigIO.read(root, MapConfig.defaults(), warnings);
        assertEquals(MapConfig.defaults().web().port(), config.web().port());
        assertEquals(MapConfig.defaults().web().threads(), config.web().threads());
        assertEquals(MapConfig.defaults().render().workers(), config.render().workers());
        assertTrue(config.players().show());
        assertEquals(4, warnings.size(), () -> "warnings: " + warnings);
    }

    @Test
    void validValuesAreApplied() {
        JsonObject root = Jsonc.parse("""
                {
                  "web": {"bind": "0.0.0.0", "port": 9000},
                  "render": {"zoom-levels": 6, "height-shading": false},
                  "scan": {"animated-textures": "average", "exclude-mods": ["foo", "bar"]}
                }
                """).getAsJsonObject();
        List<String> warnings = new ArrayList<>();
        MapConfig config = ConfigIO.read(root, MapConfig.defaults(), warnings);
        assertEquals("0.0.0.0", config.web().bind());
        assertEquals(9000, config.web().port());
        assertEquals(6, config.render().zoomLevels());
        assertFalse(config.render().heightShading());
        assertEquals("average", config.scan().animatedTextures());
        assertEquals(List.of("foo", "bar"), config.scan().excludeMods());
        assertTrue(warnings.isEmpty(), () -> "warnings: " + warnings);
    }

    @Test
    void colorParsing() {
        assertEquals(0xFF7F7F7F, ConfigIO.parseColor("#7f7f7f"));
        assertEquals(0x40FFFFFF, ConfigIO.parseColor("#40ffffff"));
        assertEquals(0xFFABCDEF, ConfigIO.parseColor("ABCDEF"));
        assertNull(ConfigIO.parseColor("#12345"));
        assertNull(ConfigIO.parseColor("red"));
        assertNull(ConfigIO.parseColor(null));
    }

    @Test
    void corruptFileIsQuarantinedAndReplaced(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.jsonc");
        Files.writeString(file, "{not json at all");
        MapConfig config = ConfigIO.loadOrCreate(file);
        assertEquals(MapConfig.defaults(), config);
        assertTrue(Files.exists(dir.resolve("config.jsonc.corrupt-1")), "corrupt file kept for inspection");
        // and the replacement template parses
        assertEquals(MapConfig.defaults(), ConfigIO.loadOrCreate(file));
    }

    @Test
    void missingFileCreatesCommentedTemplate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.jsonc");
        ConfigIO.loadOrCreate(file);
        String written = Files.readString(file);
        assertTrue(written.contains("//"), "template must contain comments");
        assertTrue(written.contains("\"bind\""));
    }
}
