package net.explorersfriend.web;

import net.explorersfriend.testutil.TestImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapHttpServerTest {

    @TempDir
    static Path tilesDir;

    static MapHttpServer server;
    static HttpClient client;
    static String base;

    @BeforeAll
    static void startServer() throws Exception {
        Files.createDirectories(tilesDir.resolve("world/0"));
        Files.write(tilesDir.resolve("world/0/0_0.png"), TestImages.solidPng(4, 4, 0xFF123456));

        MapHttpServer.DataSource data = new MapHttpServer.DataSource() {
            @Override
            public byte[] playersJson() {
                return "{\"players\":[]}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] statusJson() {
                return "{\"ready\":true}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] worldsJson() {
                return "{\"worlds\":[]}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Path tileFile(String slug, int zoom, int x, int z) {
                if (!"world".equals(slug) || zoom > 4) {
                    return null;
                }
                return tilesDir.resolve(slug).resolve(Integer.toString(zoom)).resolve(x + "_" + z + ".png");
            }
        };
        server = new MapHttpServer("127.0.0.1", 0, 2, 16, 10, true, data, "test");
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesJsonEndpointsWithSecurityHeaders() throws Exception {
        HttpResponse<String> players = get("/api/players");
        assertEquals(200, players.statusCode());
        assertTrue(players.body().contains("players"));
        assertEquals("no-store", players.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", players.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(200, get("/api/status").statusCode());
        assertEquals(200, get("/api/worlds").statusCode());
    }

    @Test
    void servesIndexWithEtagAnd304() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("<canvas"), "embedded UI is served");
        String etag = index.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag);
        HttpResponse<String> revalidated = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .header("If-None-Match", etag).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(304, revalidated.statusCode());
    }

    @Test
    void servesTilesWithEtagAnd304() throws Exception {
        HttpResponse<String> tile = get("/tiles/world/0/0_0.png");
        assertEquals(200, tile.statusCode());
        assertEquals("image/png", tile.headers().firstValue("Content-Type").orElse(""));
        String etag = tile.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag);
        HttpResponse<String> revalidated = client.send(
                HttpRequest.newBuilder(URI.create(base + "/tiles/world/0/0_0.png"))
                        .header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, revalidated.statusCode());
    }

    @Test
    void missingTilesAndUnknownDimensionsAre404() throws Exception {
        assertEquals(404, get("/tiles/world/0/99_99.png").statusCode());
        assertEquals(404, get("/tiles/unknown/0/0_0.png").statusCode());
        assertEquals(404, get("/tiles/world/9/0_0.png").statusCode(), "zoom beyond limit rejected");
    }

    @Test
    void pathTraversalAttemptsAreBlocked() throws Exception {
        assertEquals(404, get("/tiles/../secret/0/0_0.png").statusCode());
        assertEquals(404, get("/tiles/world/0/..%2f..%2fsecret.png").statusCode());
        assertEquals(404, get("/%2e%2e/%2e%2e/windows/win.ini").statusCode());
        assertEquals(404, get("/web/../../gradle.properties").statusCode());
        assertEquals(404, get("/app.js/../../../secret").statusCode());
    }

    @Test
    void nonGetMethodsAreRejected() throws Exception {
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "/api/players"))
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void oversizedUrisAreRejected() throws Exception {
        assertEquals(414, get("/" + "a".repeat(600)).statusCode());
    }

    @Test
    void gzipIsAppliedForJsonWhenAccepted() throws Exception {
        // status JSON here is < 512 bytes → identity; verify big static asset instead
        HttpResponse<byte[]> raw = client.send(HttpRequest.newBuilder(URI.create(base + "/app.js"))
                        .header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, raw.statusCode());
        assertEquals("gzip", raw.headers().firstValue("Content-Encoding").orElse(""));
        assertTrue(raw.body().length > 2 && (raw.body()[0] & 0xFF) == 0x1F && (raw.body()[1] & 0xFF) == 0x8B,
                "body is gzip-compressed");
    }
}
