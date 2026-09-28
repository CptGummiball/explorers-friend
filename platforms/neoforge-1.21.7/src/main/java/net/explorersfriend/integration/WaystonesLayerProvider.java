package net.explorersfriend.integration;

import net.explorersfriend.config.MapConfig;
import net.explorersfriend.waystone.WaystonePoint;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/** Optional Waystones integration is unavailable until an API build is validated for this target. */
public final class WaystonesLayerProvider {
    private WaystonesLayerProvider() { }

    public static boolean isAvailable() { return false; }

    public static List<WaystonePoint> collect(MinecraftServer server, MapConfig.Waystones config) {
        return List.of();
    }
}
