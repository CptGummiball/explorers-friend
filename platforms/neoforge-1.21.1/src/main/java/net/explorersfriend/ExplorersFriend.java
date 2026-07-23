package net.explorersfriend;

import net.explorersfriend.core.MapService;
import net.explorersfriend.platform.PlatformInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("explorersfriend")
public final class ExplorersFriend {

    public static final Logger LOGGER = LoggerFactory.getLogger("ExplorersFriend");
    public static final String MOD_NAME = "The Explorer's Friend";

    public ExplorersFriend() {
        String mcVersion = ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        String modVersion = ModList.get().getModContainerById("explorersfriend")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        PlatformInfo.set(new PlatformInfo("neoforge", mcVersion, "neoforge-1.21.1", modVersion));
        LOGGER.info("[ExplorersFriend/Init] {} v{} initializing (Java {}, {} mods loaded)",
                MOD_NAME, modVersion, Runtime.version().feature(), ModList.get().size());
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        MapService.create(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MapService.ifPresent(MapService::startAsync);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MapService.shutdownCurrent();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MapService.ifPresent(MapService::onEndTick);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getChunk() instanceof LevelChunk chunk) {
            MapService.ifPresent(service -> service.onChunkLoad(level, chunk));
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getChunk() instanceof LevelChunk chunk) {
            MapService.ifPresent(service -> service.onChunkUnload(level, chunk));
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        net.explorersfriend.command.MapCommands.register(event.getDispatcher(),
                event.getBuildContext(), event.getCommandSelection());
        net.explorersfriend.command.MarkerCommands.register(event.getDispatcher(),
                event.getBuildContext(), event.getCommandSelection());
    }
}
