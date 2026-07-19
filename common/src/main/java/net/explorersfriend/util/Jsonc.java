package net.explorersfriend.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Minimal "JSON with comments" support for config files: strips {@code //} and
 * {@code /* *}{@code /} comments outside of strings, then parses with Gson in lenient
 * mode (which additionally tolerates trailing commas). Stateless, thread-safe.
 */
public final class Jsonc {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Jsonc() {
    }

    public static JsonElement parse(String jsoncText) throws JsonParseException {
        return JsonParser.parseString(stripComments(jsoncText));
    }

    /** Removes line and block comments that are not inside string literals. */
    public static String stripComments(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaped = false;
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char next = input.charAt(i + 1);
                if (next == '/') {
                    while (i < n && input.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < n && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) {
                        // keep newlines so parse-error line numbers stay meaningful
                        if (input.charAt(i) == '\n') {
                            out.append('\n');
                        }
                        i++;
                    }
                    i = Math.min(n, i + 2);
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
