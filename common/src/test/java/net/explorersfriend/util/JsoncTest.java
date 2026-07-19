package net.explorersfriend.util;

import com.google.gson.JsonObject;
import net.explorersfriend.config.ConfigIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsoncTest {

    @Test
    void stripsLineAndBlockComments() {
        String input = """
                {
                  // line comment
                  "a": 1, /* block
                  comment */ "b": 2
                }
                """;
        JsonObject parsed = Jsonc.parse(input).getAsJsonObject();
        assertEquals(1, parsed.get("a").getAsInt());
        assertEquals(2, parsed.get("b").getAsInt());
    }

    @Test
    void keepsCommentLikeContentInsideStrings() {
        String input = "{\"url\": \"https://example.org/path\", \"note\": \"a /* not a comment */ b\"}";
        JsonObject parsed = Jsonc.parse(input).getAsJsonObject();
        assertEquals("https://example.org/path", parsed.get("url").getAsString());
        assertEquals("a /* not a comment */ b", parsed.get("note").getAsString());
    }

    @Test
    void handlesEscapedQuotes() {
        String input = "{\"a\": \"quote \\\" // still string\"} // trailing";
        JsonObject parsed = Jsonc.parse(input).getAsJsonObject();
        assertEquals("quote \" // still string", parsed.get("a").getAsString());
    }

    @Test
    void defaultConfigTemplateIsParseable() {
        JsonObject parsed = Jsonc.parse(ConfigIO.defaultTemplate()).getAsJsonObject();
        assertTrue(parsed.has("web"));
        assertTrue(parsed.has("render"));
        assertTrue(parsed.has("scan"));
        assertEquals("127.0.0.1", parsed.getAsJsonObject("web").get("bind").getAsString());
    }
}
