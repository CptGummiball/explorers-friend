package net.explorersfriend.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.player.SkinImages;
import net.explorersfriend.util.MoreFiles;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Player head icons for the web map.
 *
 * <p>Strategy: the skin URL comes from the player's own game profile (the "textures"
 * property the server already holds — no extra Mojang profile lookup). The URL host is
 * pinned to {@code textures.minecraft.net} (SSRF protection: nothing else is ever
 * fetched, regardless of what a spoofed profile claims). Downloads run on the scan
 * pool with a global concurrency cap, timeout and negative caching; heads (8×8 base +
 * hat layer, upscaled to 64×64) are cached in memory and on disk with a configurable
 * TTL. Offline-mode servers and failures fall back to a built-in neutral head. The
 * server never blocks on any of this.</p>
 */
public final class SkinService {

    private static final String ALLOWED_HOST = "textures.minecraft.net";
    private static final long NEGATIVE_CACHE_MS = 60 * 60 * 1000L;
    private static final int MAX_SKIN_BYTES = 256 * 1024;

    private final Path cacheDir;
    private final MapConfig.Players config;
    private final ExecutorService fetchPool;
    private final Semaphore concurrent = new Semaphore(2);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ConcurrentHashMap<String, byte[]> headsByUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nextAttemptByUuid = new ConcurrentHashMap<>();
    private final byte[] defaultHead;

    public SkinService(Path cacheDir, MapConfig.Players config, ExecutorService fetchPool) {
        this.cacheDir = cacheDir;
        this.config = config;
        this.fetchPool = fetchPool;
        this.defaultHead = buildDefaultHead();
    }

    /** Server thread, called from the player sampler: cheap check, async work. */
    public void requestHead(ServerPlayer player) {
        String uuid = player.getStringUUID();
        long now = System.currentTimeMillis();
        Long nextAttempt = nextAttemptByUuid.get(uuid);
        if (nextAttempt != null && now < nextAttempt) {
            return;
        }
        nextAttemptByUuid.put(uuid, now + config.skinCacheHours() * 3600_000L);
        String texturesBase64 = player.getGameProfile().getProperties().get("textures").stream()
                .findFirst().map(com.mojang.authlib.properties.Property::value).orElse(null);
        fetchPool.submit(() -> resolveAsync(uuid, texturesBase64));
    }

    /** @return head PNG for the uuid (cached, disk, or the built-in default). */
    public byte[] headPng(String uuid) {
        if (!uuid.matches("[0-9a-fA-F-]{32,36}")) {
            return defaultHead;
        }
        byte[] cached = headsByUuid.get(uuid);
        if (cached != null) {
            return cached;
        }
        Path file = cacheDir.resolve(uuid.toLowerCase(Locale.ROOT) + ".png");
        try {
            if (Files.isRegularFile(file)) {
                byte[] bytes = Files.readAllBytes(file);
                headsByUuid.put(uuid, bytes);
                return bytes;
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return defaultHead;
    }

    private void resolveAsync(String uuid, String texturesBase64) {
        try {
            Path file = cacheDir.resolve(uuid.toLowerCase(Locale.ROOT) + ".png");
            if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis()
                    > System.currentTimeMillis() - config.skinCacheHours() * 3600_000L) {
                headsByUuid.putIfAbsent(uuid, Files.readAllBytes(file));
                return; // fresh enough
            }
            if (!config.allowExternalSkinLookup() || texturesBase64 == null) {
                return; // offline mode / disabled: default head stays
            }
            String url = SkinImages.skinUrlFromTexturesProperty(texturesBase64);
            if (url == null) {
                return;
            }
            concurrent.acquire();
            byte[] skin;
            try {
                skin = download(url);
            } finally {
                concurrent.release();
            }
            byte[] head = SkinImages.composeHead(skin);
            MoreFiles.writeAtomic(file, head);
            headsByUuid.put(uuid, head);
            ExplorersFriend.LOGGER.debug("[ExplorersFriend/Players] Cached skin head for {}", uuid);
        } catch (Exception e) {
            nextAttemptByUuid.put(uuid, System.currentTimeMillis() + NEGATIVE_CACHE_MS);
            ExplorersFriend.LOGGER.debug("[ExplorersFriend/Players] Skin fetch for {} failed: {}",
                    uuid, e.toString());
        }
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            byte[] data = in.readNBytes(MAX_SKIN_BYTES + 1);
            if (data.length > MAX_SKIN_BYTES) {
                throw new IOException("skin exceeds size limit");
            }
            return data;
        }
    }

    /** Neutral built-in head (own pixel art) for offline mode and failures. */
    private static byte[] buildDefaultHead() {
        int skinTone = 0xFFB58F6B;
        int hair = 0xFF4A3628;
        int eyes = 0xFF3C44AA;
        int[][] grid = new int[8][8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                grid[y][x] = y < 2 ? hair : skinTone;
            }
        }
        grid[2][0] = hair;
        grid[2][7] = hair;
        grid[4][2] = eyes;
        grid[4][5] = eyes;
        grid[6][3] = 0xFF8F6B4F;
        grid[6][4] = 0xFF8F6B4F;
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        head.setRGB(x * 8 + dx, y * 8 + dy, grid[y][x]);
                    }
                }
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(head, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
