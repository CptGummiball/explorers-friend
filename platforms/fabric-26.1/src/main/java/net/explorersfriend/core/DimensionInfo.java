package net.explorersfriend.core;

import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;

/** Static facts about one enabled dimension, collected once on the server thread. */
public record DimensionInfo(
        String id,
        String slug,
        ServerLevel world,
        Path regionDir,
        int spawnX,
        int spawnZ,
        boolean hasCeiling) {
}
