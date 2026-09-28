package net.explorersfriend.claims;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.config.MapConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Provider detection for this Minecraft/NeoForge target. */
public final class ClaimProviders {
    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    private ClaimProviders() { }

    public static List<ClaimProvider> detect(MinecraftServer server, MapConfig.Claims config,
                                             Path configDir, Path dataDir) {
        List<ClaimProvider> providers = new ArrayList<>();
        Path importFile = configDir.resolve("claims-import.jsonc");
        if ((config.enabledProviders().contains("*") || config.enabledProviders().contains("jsonimport"))
                && Files.isRegularFile(importFile)) {
            providers.add(new net.explorersfriend.claims.provider.JsonImportClaimProvider(importFile));
            LOGGER.info("[ExplorersFriend/Claims] JSON import: active ({})", importFile.getFileName());
        }
        LOGGER.info("[ExplorersFriend/Claims] Active providers: {}", providers.size());
        return providers;
    }
}
