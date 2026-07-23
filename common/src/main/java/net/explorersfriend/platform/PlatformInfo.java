package net.explorersfriend.platform;

import net.explorersfriend.util.Log;

/**
 * Identity of the platform adapter that bootstrapped the map service. Set exactly
 * once by the platform module before the core starts; the web status endpoint and
 * the startup log read it. This record and the snapshot types (TileChunkData,
 * overlay/claim/marker/player models) form the platform seam: adapters construct
 * neutral immutable data, the core never sees a Minecraft, Bukkit or loader class.
 */
public record PlatformInfo(
        String platformId,        // "fabric" | "quilt" | "neoforge" | "forge" | "spigot" | "paper"
        String minecraftVersion,  // runtime-detected, e.g. "1.21.1"
        String adapterId,         // module id, e.g. "fabric-1.21.1" or "spigot"
        String modVersion) {

    private static volatile PlatformInfo current =
            new PlatformInfo("unknown", "unknown", "unknown", "unknown");

    public static void set(PlatformInfo info) {
        current = info;
        Log.LOGGER.info("[ExplorersFriend/Platform] Platform detected: {}", info.platformId());
        Log.LOGGER.info("[ExplorersFriend/Platform] Minecraft version: {}", info.minecraftVersion());
        Log.LOGGER.info("[ExplorersFriend/Platform] Platform adapter: {}", info.adapterId());
    }

    public static PlatformInfo get() {
        return current;
    }
}
