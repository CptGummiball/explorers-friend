package net.explorersfriend;

import net.explorersfriend.command.MapCommands;
import net.explorersfriend.core.MapService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point. Runs in every environment ("main" entrypoint only); no client-only
 * classes are referenced anywhere in this mod, so the same JAR works on a dedicated
 * server, in a client installation, and on the integrated singleplayer server.
 *
 * <p>All heavy lifting lives in {@link MapService}, which is created per server
 * instance on SERVER_STARTING and torn down on SERVER_STOPPING. The integrated
 * server fires the same lifecycle events, so singleplayer works without extra code.</p>
 */
public final class ExplorersFriend implements ModInitializer {

    public static final String MOD_ID = "explorersfriend";
    public static final String MOD_NAME = "The Explorer's Friend";
    public static final Logger LOGGER = LoggerFactory.getLogger("ExplorersFriend");

    @Override
    public void onInitialize() {
        String modVersion = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("[ExplorersFriend/Init] {} v{} initializing (Java {}, {} mods loaded)",
                MOD_NAME, modVersion, Runtime.version().feature(),
                FabricLoader.getInstance().getAllMods().size());

        ServerLifecycleEvents.SERVER_STARTING.register(MapService::create);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> MapService.ifPresent(MapService::startAsync));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MapService.shutdownCurrent());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> MapService.ifPresent(MapService::onEndTick));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(
                (world, chunk, newlyGenerated) -> MapService.ifPresent(service -> service.onChunkLoad(world, chunk)));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_UNLOAD.register(
                (world, chunk) -> MapService.ifPresent(service -> service.onChunkUnload(world, chunk)));

        MapCommands.register();
        net.explorersfriend.command.MarkerCommands.register();
    }
}
