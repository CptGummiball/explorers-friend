package net.explorersfriend.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.explorersfriend.util.Log;
import net.explorersfriend.util.NamedThreadFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Embedded map web server on the JDK's {@code com.sun.net.httpserver} — no third-party
 * dependency, two worker threads by default, a couple of MB of footprint.
 *
 * <p>Security posture:</p>
 * <ul>
 *   <li>only GET/HEAD; everything else is 405</li>
 *   <li>static files come exclusively from an explicit classpath whitelist — no
 *       filesystem paths are ever derived from the URL</li>
 *   <li>tile URLs are parsed by a strict regex; the dimension slug must be a known,
 *       enabled dimension; coordinates are bounded integers → path traversal is
 *       structurally impossible</li>
 *   <li>request URIs are length-limited, connections are capped by a semaphore
 *       (503 beyond the limit), and request/response times are bounded</li>
 * </ul>
 */
public final class MapHttpServer implements AutoCloseable {

    private static final Logger LOGGER = Log.LOGGER;
    private static final Pattern TILE_PATTERN =
            Pattern.compile("/tiles/([a-z0-9_.\\-]{1,64})/(\\d{1,2})/(-?\\d{1,7})_(-?\\d{1,7})\\.png");
    private static final int MAX_URI_LENGTH = 512;

    /** Data the HTTP layer may serve; every method must be thread-safe and non-blocking. */
    public interface DataSource {
        byte[] playersJson();

        byte[] statusJson();

        byte[] worldsJson();

        /** @return tile file path for an enabled dimension, or null when unknown/out of range. */
        Path tileFile(String dimensionSlug, int zoom, int tileX, int tileZ);
    }

    private record StaticAsset(byte[] bytes, String contentType, String etag) {
    }

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final Semaphore connectionLimit;
    private final DataSource data;
    private final boolean gzipEnabled;
    private final Map<String, StaticAsset> staticAssets;
    private final String bindDescription;
    private volatile OverlayWebService overlayService;

    public MapHttpServer(String bind, int port, int threads, int connectionLimitCount,
                         int idleTimeoutSeconds, boolean gzipEnabled, DataSource data,
                         String modVersion) throws IOException {
        this.data = data;
        this.gzipEnabled = gzipEnabled;
        this.connectionLimit = new Semaphore(connectionLimitCount);
        this.staticAssets = loadStaticAssets(modVersion);

        // Bounded request/response lifetimes for the JDK server (global, set once).
        System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(idleTimeoutSeconds));
        System.setProperty("sun.net.httpserver.maxRspTime", Integer.toString(Math.max(60, idleTimeoutSeconds * 2)));

        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), new NamedThreadFactory("EF-Web"),
                new ThreadPoolExecutor.AbortPolicy());
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
        this.server.start();
        this.bindDescription = bind + ":" + port;
        LOGGER.info("[ExplorersFriend/Web] Listening on {}", bindDescription);
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind)) {
            LOGGER.info("[ExplorersFriend/Web] Note: the map is bound to a non-loopback address "
                    + "and may be reachable from the network");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!connectionLimit.tryAcquire()) {
            sendError(exchange, 503, "busy");
            return;
        }
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().add("Allow", "GET, HEAD");
                sendError(exchange, 405, "method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || exchange.getRequestURI().toString().length() > MAX_URI_LENGTH) {
                sendError(exchange, 414, "URI too long");
                return;
            }
            OverlayWebService service = overlayService;
            if (service != null && service.handles(path)) {
                sendOverlayResponse(exchange, service.handle(path, parseQuery(exchange), 
                        exchange.getRequestHeaders().getFirst("If-None-Match")));
                return;
            }
            switch (path) {
                case "/api/players" -> sendJson(exchange, data.playersJson(), "no-store");
                case "/api/status" -> sendJson(exchange, data.statusJson(), "no-store");
                case "/api/worlds" -> sendJson(exchange, data.worldsJson(), "no-cache");
                default -> {
                    Matcher tile = TILE_PATTERN.matcher(path);
                    if (tile.matches()) {
                        handleTile(exchange, tile);
                        return;
                    }
                    StaticAsset asset = staticAssets.get(path);
                    if (asset != null) {
                        sendStatic(exchange, asset);
                        return;
                    }
                    sendError(exchange, 404, "not found");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ExplorersFriend/Web] Request failed: {}", e.toString());
            try {
                sendError(exchange, 500, "internal error");
            } catch (IOException ignored) {
                // client gone
            }
        } finally {
            connectionLimit.release();
            exchange.close();
        }
    }

    private void handleTile(HttpExchange exchange, Matcher tile) throws IOException {
        String slug = tile.group(1);
        int zoom = Integer.parseInt(tile.group(2));
        int tileX = Integer.parseInt(tile.group(3));
        int tileZ = Integer.parseInt(tile.group(4));
        Path file = data.tileFile(slug, zoom, tileX, tileZ);
        if (file == null || !Files.isRegularFile(file)) {
            sendError(exchange, 404, "no tile");
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String etag = "\"" + attributes.size() + "-" + attributes.lastModifiedTime().toMillis() + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        byte[] bytes = Files.readAllBytes(file); // tiles are ≤ a few hundred KB
        sendBody(exchange, 200, bytes, false);
    }

    private void sendStatic(HttpExchange exchange, StaticAsset asset) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        exchange.getResponseHeaders().set("ETag", asset.etag());
        exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (asset.etag().equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBody(exchange, 200, asset.bytes(), isCompressible(asset.contentType()));
    }

    private void sendJson(HttpExchange exchange, byte[] json, String cacheControl) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        sendBody(exchange, 200, json, true);
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            out.put(java.net.URLDecoder.decode(pair.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private void sendOverlayResponse(HttpExchange exchange, OverlayWebService.Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (response.cacheControl() != null) {
            exchange.getResponseHeaders().set("Cache-Control", response.cacheControl());
        }
        if (response.etag() != null) {
            exchange.getResponseHeaders().set("ETag", response.etag());
        }
        if (response.status() == 304) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBody(exchange, response.status(), response.body(), response.compressible());
    }

    private void sendBody(HttpExchange exchange, int status, byte[] bytes, boolean compressible) throws IOException {
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        boolean gzip = gzipEnabled && compressible && bytes.length > 512 && acceptsGzip(exchange);
        if (gzip) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        if (gzip) {
            exchange.sendResponseHeaders(status, 0); // chunked
            try (GZIPOutputStream out = new GZIPOutputStream(exchange.getResponseBody(), 8192)) {
                out.write(bytes);
            }
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static boolean isCompressible(String contentType) {
        return contentType.startsWith("text/") || contentType.startsWith("application/json")
                || contentType.startsWith("application/javascript") || contentType.contains("svg");
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Loads the embedded web UI from the classpath (explicit whitelist, no directories). */
    private static Map<String, StaticAsset> loadStaticAssets(String modVersion) {
        Map<String, String> files = Map.of(
                "/", "web/index.html",
                "/index.html", "web/index.html",
                "/app.js", "web/app.js",
                "/style.css", "web/style.css",
                "/favicon.svg", "web/favicon.svg");
        Map<String, String> types = Map.of(
                "html", "text/html; charset=utf-8",
                "js", "application/javascript; charset=utf-8",
                "css", "text/css; charset=utf-8",
                "svg", "image/svg+xml");
        Map<String, StaticAsset> out = new HashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String resource = entry.getValue();
            try (InputStream in = MapHttpServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.error("[ExplorersFriend/Web] Embedded asset {} is missing from the JAR", resource);
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                String extension = resource.substring(resource.lastIndexOf('.') + 1);
                String etag = "\"" + modVersion + "-" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"";
                out.put(entry.getKey(), new StaticAsset(bytes,
                        types.getOrDefault(extension, "application/octet-stream"), etag));
            } catch (IOException e) {
                LOGGER.error("[ExplorersFriend/Web] Could not load embedded asset {}: {}", resource, e.toString());
            }
        }
        return out;
    }

    /** Attaches the versioned overlay API (/api/v1/*, /icons/*, /metrics). */
    public void setOverlayService(OverlayWebService service) {
        this.overlayService = service;
    }

    public String bindDescription() {
        return bindDescription;
    }

    /** Actual bound port (relevant when configured with port 0 in tests). */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
        LOGGER.info("[ExplorersFriend/Web] Web server stopped");
    }
}
