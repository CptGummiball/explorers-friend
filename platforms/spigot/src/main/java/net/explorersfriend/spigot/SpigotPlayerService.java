package net.explorersfriend.spigot;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.player.PlayerLayer;
import net.explorersfriend.player.SkinImages;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Live player layer for the Spigot/Paper backend. A synchronous sampler task
 * (main thread, interval from players.update-interval-seconds) publishes immutable
 * points into the shared {@link PlayerLayer} and records each online player's skin
 * URL (Bukkit PlayerProfile textures - no Mojang session lookups). Head PNGs are
 * composed on demand by the web thread from a bounded in-memory cache; downloads
 * stay pinned to the official texture host exactly like the loader backends.
 */
final class SpigotPlayerService {

    private final PlayerLayer layer;
    private final MapConfig.Players config;
    private final Function<World, String> dimensionId;
    private final Map<String, String> skinUrlByUuid = new ConcurrentHashMap<>();
    private final Map<String, byte[]> headCache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();
    private volatile byte[] defaultHead;

    SpigotPlayerService(MapConfig.Players config, Function<World, String> dimensionId) {
        this.layer = new PlayerLayer(playersConfig(config));
        this.config = config;
        this.dimensionId = dimensionId;
    }

    private static MapConfig.Players playersConfig(MapConfig.Players config) {
        return config;
    }

    PlayerLayer layer() {
        return layer;
    }

    /** Runs on the main thread. */
    void sample() {
        List<PlayerLayer.Point> points = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (config.hideSpectators() && player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (config.hideInvisible() && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                continue;
            }
            String slug = TileStore.dimensionSlug(dimensionId.apply(player.getWorld()));
            points.add(new PlayerLayer.Point(
                    player.getUniqueId().toString(),
                    player.getName(),
                    slug,
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ(),
                    (int) player.getLocation().getYaw(),
                    0));
            try {
                URL skin = player.getPlayerProfile().getTextures().getSkin();
                if (skin != null) {
                    skinUrlByUuid.put(player.getUniqueId().toString(), skin.toString());
                }
            } catch (Throwable t) {
                // profile textures unavailable (offline mode etc.) - default head applies
            }
        }
        layer.publishSample(points);
    }

    /** Called from web threads; always returns PNG bytes. */
    byte[] headPng(String uuid) {
        byte[] cached = headCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        String url = skinUrlByUuid.get(uuid);
        if (url != null && config.allowExternalSkinLookup()) {
            try {
                URI uri = URI.create(url);
                if (SkinImages.ALLOWED_HOST.equals(uri.getHost())) {
                    HttpResponse<byte[]> response = http.send(
                            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        byte[] head = SkinImages.composeHead(response.body());
                        if (headCache.size() < 512) {
                            headCache.put(uuid, head);
                        }
                        return head;
                    }
                }
            } catch (Exception e) {
                Log.LOGGER.debug("[ExplorersFriend/Players] Skin download failed for {}: {}",
                        uuid, e.toString());
            }
        }
        return defaultHead();
    }

    private byte[] defaultHead() {
        byte[] head = defaultHead;
        if (head == null) {
            try {
                BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        img.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF6D8A9C : 0xFF55707F);
                    }
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", out);
                head = out.toByteArray();
            } catch (Exception e) {
                head = new byte[0];
            }
            defaultHead = head;
        }
        return head;
    }
}
