package net.explorersfriend.claims;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.config.MapConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time provider detection at server start. Adapter classes for optional mods are
 * only referenced inside their {@code isModLoaded} branch, so absent mods cause no
 * class loading, no scans and no {@code ClassNotFoundException}. Adding support for
 * another claim system = implement {@link ClaimProvider} + add a branch here.
 */
public final class ClaimProviders {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    private ClaimProviders() {
    }

    public static List<ClaimProvider> detect(MinecraftServer server, MapConfig.Claims config, Path configDir) {
        LOGGER.info("[ExplorersFriend/Claims] Detecting supported claim providers...");
        List<ClaimProvider> providers = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();

        LOGGER.info("[ExplorersFriend/Claims] FTB Chunks: no Fabric build exists for this "
                + "Minecraft version (adapter not included; see docs/CLAIM_PROVIDERS.md)");

        if (loader.isModLoaded("openpartiesandclaims")) {
            if (providerEnabled(config, "openpartiesandclaims")) {
                providers.add(new net.explorersfriend.claims.provider.OpacClaimProvider(server));
                LOGGER.info("[ExplorersFriend/Claims] Open Parties and Claims: detected");
            } else {
                LOGGER.info("[ExplorersFriend/Claims] Open Parties and Claims: detected but disabled by config");
            }
        } else {
            LOGGER.info("[ExplorersFriend/Claims] Open Parties and Claims: not installed");
        }

        LOGGER.info("[ExplorersFriend/Claims] GriefPrevention: unavailable on this platform "
                + "(Bukkit/Paper plugin, no Fabric port - see docs/CLAIM_PROVIDERS.md; "
                + "use the JSON import as a bridge)");

        Path importFile = configDir.resolve("claims-import.jsonc");
        if (providerEnabled(config, "jsonimport") && Files.isRegularFile(importFile)) {
            providers.add(new net.explorersfriend.claims.provider.JsonImportClaimProvider(importFile));
            LOGGER.info("[ExplorersFriend/Claims] JSON import: active ({})", importFile.getFileName());
        }

        LOGGER.info("[ExplorersFriend/Claims] Active providers: {}", providers.size());
        return providers;
    }

    private static boolean providerEnabled(MapConfig.Claims config, String providerId) {
        return config.enabledProviders().contains("*") || config.enabledProviders().contains(providerId);
    }
}
