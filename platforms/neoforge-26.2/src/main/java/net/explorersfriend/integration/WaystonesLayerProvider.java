package net.explorersfriend.integration;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.waystone.WaystonePoint;
import net.neoforged.fml.ModList;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the Waystones mod (net.blay09.mods.waystones.api, compile-only).
 * Only the isolated {@code Api} holder touches Waystones classes, so nothing is
 * class-loaded unless the mod is actually installed. Sharestones, shard-bound and
 * transient waystones never appear on the map; the only-global switch restricts the
 * layer further. Collection runs on the server thread (cheap: the storage is a small
 * in-memory list) on the interval configured in waystones.refresh-seconds.
 */
public final class WaystonesLayerProvider {

    private WaystonesLayerProvider() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded("waystones");
    }

    public static List<WaystonePoint> collect(MinecraftServer server, MapConfig.Waystones config) {
        try {
            return Api.collect(server, config);
        } catch (Throwable t) {
            net.explorersfriend.util.Log.LOGGER.warn(
                    "[ExplorersFriend/Waystones] Incompatible Waystones API: {}", t.toString());
            return List.of();
        }
    }

    private static final class Api {
        static List<WaystonePoint> collect(MinecraftServer server, MapConfig.Waystones config) {
            List<WaystonePoint> out = new ArrayList<>();
            net.blay09.mods.waystones.api.WaystonesAPI.getAllWaystones(server).forEach(ws -> {
                if (!ws.isValid() || ws.isTransient()) {
                    return;
                }
                net.blay09.mods.waystones.api.WaystoneVisibility visibility = ws.getVisibility();
                boolean global = visibility == net.blay09.mods.waystones.api.WaystoneVisibility.GLOBAL;
                if (config.onlyGlobal() && !global) {
                    return;
                }
                if (!global && visibility != net.blay09.mods.waystones.api.WaystoneVisibility.ACTIVATION) {
                    return;   // sharestones / shard-only stay private
                }
                String dimensionId = ws.getDimension().identifier().toString();
                String slug = TileStore.dimensionSlug(dimensionId);
                if (config.disabledWorlds().contains(dimensionId)
                        || config.disabledWorlds().contains(slug)) {
                    return;
                }
                String name = ws.getName().getString();
                if (name == null || name.isBlank()) {
                    return;
                }
                String owner = config.showOwner() && ws.hasOwner()
                        ? ws.getOwnerUsername().orElse(null) : null;
                out.add(new WaystonePoint(
                        "ws:" + ws.getWaystoneUid(),
                        slug,
                        ws.getPos().getX(), ws.getPos().getY(), ws.getPos().getZ(),
                        name, owner, global));
            });
            return out;
        }
    }
}
