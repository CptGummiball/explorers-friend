package net.explorersfriend.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.util.MoreFiles;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Locates a JAR containing the vanilla <em>client</em> assets (block models + textures).
 *
 * <p>Dedicated server JARs do not contain block textures, so on a dedicated server this
 * class can download the official client JAR once from Mojang's public distribution
 * servers (the same files the launcher fetches), verify its SHA-1 against the version
 * manifest, and cache it. In a client installation or the integrated server the running
 * game JAR already contains the assets and nothing is downloaded.</p>
 *
 * <p>Only static-data reads happen here; no Minecraft client <em>classes</em> are ever
 * referenced. Network access is bounded by timeouts and happens exclusively on the scan
 * pool during startup.</p>
 */
public final class VanillaAssetLocator {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** Asset that only exists in JARs carrying client resources. */
    private static final String PROBE_PATH = "assets/minecraft/models/block/stone.json";

    private VanillaAssetLocator() {
    }

    /**
     * @param gameJarCandidate the JAR the "minecraft" mod container points at (may lack assets)
     * @param cacheDir         cache directory for the downloaded client JAR
     * @param gameVersion      exact version id, e.g. "1.21.1"
     * @param allowDownload    config switch {@code scan.download-vanilla-assets}
     * @return path to a JAR with client assets, or empty when unavailable
     */
    public static Optional<Path> locate(Path gameJarCandidate, Path cacheDir, String gameVersion, boolean allowDownload) {
        if (gameJarCandidate != null && containsClientAssets(gameJarCandidate)) {
            LOGGER.info("[ExplorersFriend/Scanner] Vanilla assets found in running game JAR ({})",
                    gameJarCandidate.getFileName());
            return Optional.of(gameJarCandidate);
        }
        Path cached = cacheDir.resolve("vanilla-client-" + gameVersion + ".jar");
        if (containsClientAssets(cached)) {
            LOGGER.info("[ExplorersFriend/Scanner] Vanilla assets found in cached client JAR ({})", cached.getFileName());
            return Optional.of(cached);
        }
        if (!allowDownload) {
            LOGGER.warn("[ExplorersFriend/Scanner] No vanilla client assets available and "
                    + "'scan.download-vanilla-assets' is disabled - vanilla blocks will use the built-in fallback palette");
            return Optional.empty();
        }
        try {
            download(gameVersion, cached);
            if (containsClientAssets(cached)) {
                return Optional.of(cached);
            }
            LOGGER.error("[ExplorersFriend/Scanner] Downloaded client JAR is missing expected assets");
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.error("[ExplorersFriend/Scanner] Could not download vanilla client JAR for {}: {} "
                    + "- vanilla blocks will use the built-in fallback palette", gameVersion, e.toString());
            return Optional.empty();
        }
    }

    static boolean containsClientAssets(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(PROBE_PATH) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static void download(String gameVersion, Path target) throws IOException, InterruptedException {
        LOGGER.info("[ExplorersFriend/Scanner] Downloading vanilla client JAR {} from Mojang (one-time, ~25 MB)...",
                gameVersion);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JsonObject manifest = getJson(client, MANIFEST_URL);
        String versionUrl = null;
        for (JsonElement el : manifest.getAsJsonArray("versions")) {
            JsonObject version = el.getAsJsonObject();
            if (gameVersion.equals(version.get("id").getAsString())) {
                versionUrl = version.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null || !versionUrl.startsWith("https://piston-meta.mojang.com/")) {
            throw new IOException("version " + gameVersion + " not found in Mojang manifest");
        }
        JsonObject versionJson = getJson(client, versionUrl);
        JsonObject clientDownload = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        String jarUrl = clientDownload.get("url").getAsString();
        String expectedSha1 = clientDownload.get("sha1").getAsString();
        if (!jarUrl.startsWith("https://piston-data.mojang.com/")) {
            throw new IOException("unexpected download host: " + jarUrl);
        }

        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        MoreFiles.ensureParent(tmp);
        HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        MessageDigest sha1 = sha1();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from Mojang");
        }
        try (InputStream in = new DigestInputStream(response.body(), sha1)) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = HexFormat.of().formatHex(sha1.digest());
        if (!actual.equalsIgnoreCase(expectedSha1)) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA-1 mismatch (expected " + expectedSha1 + ", got " + actual + ")");
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[ExplorersFriend/Scanner] Vanilla client JAR downloaded and verified ({})", target.getFileName());
    }

    private static JsonObject getJson(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
