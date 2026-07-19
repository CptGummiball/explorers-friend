package net.explorersfriend.world;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.player.PlayerLayer;
import net.explorersfriend.render.TileStore;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Platform sampler for the live player layer: reads entities on the server thread
 * (cheap boolean filters only — no IO, no JSON) and feeds plain points into the
 * Minecraft-independent {@link PlayerLayer}. Visibility rules: config filters,
 * per-player opt-out and every registered {@link PlayerVisibilityProviders} veto.
 */
public final class LivePlayerService {

    private final MapConfig.Players config;
    private final PlayerLayer layer;

    public LivePlayerService(MapConfig.Players config) {
        this.config = config;
        this.layer = new PlayerLayer(config);
    }

    public PlayerLayer layer() {
        return layer;
    }

    /** Runs ON the server thread. */
    public void sample(MinecraftServer server, Set<String> enabledSlugs) {
        if (!config.show()) {
            layer.clear();
            return;
        }
        int rounding = Math.max(1, config.positionRounding());
        List<PlayerLayer.Point> raw = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isDisplayable(player)) {
                continue;
            }
            String slug = TileStore.dimensionSlug(player.getEntityWorld().getRegistryKey().getValue().toString());
            if (!enabledSlugs.contains(slug) || config.disabledWorlds().contains(slug)) {
                continue;
            }
            String uuid = player.getUuidAsString();
            raw.add(new PlayerLayer.Point(uuid,
                    layer.displayName(uuid, player.getGameProfile().name()), slug,
                    Math.floorDiv((int) Math.floor(player.getX()), rounding) * rounding,
                    (int) Math.floor(player.getY()),
                    Math.floorDiv((int) Math.floor(player.getZ()), rounding) * rounding,
                    Math.round(player.getYaw()), 0));
        }
        layer.publishSample(raw);
    }

    private boolean isDisplayable(ServerPlayerEntity player) {
        if (config.hideSpectators() && player.isSpectator()) {
            return false;
        }
        if (config.hideInvisible()
                && (player.isInvisible() || player.hasStatusEffect(StatusEffects.INVISIBILITY))) {
            return false;
        }
        String name = player.getGameProfile().name();
        String uuid = player.getUuidAsString();
        for (String hidden : config.hiddenPlayers()) {
            if (hidden.equalsIgnoreCase(name) || hidden.equalsIgnoreCase(uuid)) {
                return false;
            }
        }
        for (PlayerVisibilityProviders.PlayerVisibilityProvider provider : PlayerVisibilityProviders.all()) {
            try {
                if (!provider.shouldDisplay(player)) {
                    return false;
                }
            } catch (Exception ignored) {
                return false; // broken providers must not expose anyone
            }
        }
        return true;
    }

    public void clear() {
        layer.clear();
    }
}
