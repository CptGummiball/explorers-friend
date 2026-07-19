package net.explorersfriend.core;

import net.minecraft.server.world.ServerWorld;

import java.nio.file.Path;

/** Static facts about one enabled dimension, collected once on the server thread. */
public record DimensionInfo(
        String id,
        String slug,
        ServerWorld world,
        Path regionDir,
        int spawnX,
        int spawnZ,
        boolean hasCeiling) {
}
