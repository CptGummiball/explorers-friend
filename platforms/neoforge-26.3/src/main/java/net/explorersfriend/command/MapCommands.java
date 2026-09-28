package net.explorersfriend.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.explorersfriend.core.ColorPipeline;
import net.explorersfriend.core.DimensionInfo;
import net.explorersfriend.core.MapService;
import net.explorersfriend.render.RenderScheduler;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.scan.JarRecord;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code /efmap} command tree. Every handler returns immediately; long-running work
 * is delegated to the map service's workers, which report back through
 * {@link #asyncFeedback} (always re-dispatched to the server thread).
 *
 * <p>Permissions: the whole tree needs level 2 (ops); the destructive
 * {@code cache prune} additionally needs level 3.</p>
 */
public final class MapCommands {

    private MapCommands() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        registerTree(dispatcher, registryAccess, environment);
    }

    private static void registerTree(CommandDispatcher<CommandSourceStack> dispatcher,
                                     CommandBuildContext registryAccess,
                                     Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("efmap")
                .then(Commands.literal("status")
                        .requires(EfPermissions.require("explorersfriend.command.status", 2))
                        .executes(MapCommands::status))
                .then(Commands.literal("render")
                        .requires(EfPermissions.require("explorersfriend.command.render", 2))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(context -> render(context, 0))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(64, 30_000_000))
                                        .executes(context -> render(context,
                                                IntegerArgumentType.getInteger(context, "radius"))))))
                .then(Commands.literal("update")
                        .requires(EfPermissions.require("explorersfriend.command.update", 2))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(MapCommands::update)))
                .then(Commands.literal("pause")
                        .requires(EfPermissions.require("explorersfriend.command.pause", 2)).executes(MapCommands::pause))
                .then(Commands.literal("resume")
                        .requires(EfPermissions.require("explorersfriend.command.resume", 2)).executes(MapCommands::resume))
                .then(Commands.literal("cancel")
                        .requires(EfPermissions.require("explorersfriend.command.cancel", 2))
                        .executes(context -> cancel(context, null))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(context -> cancel(context,
                                        DimensionArgument.getDimension(context, "dimension")))))
                .then(Commands.literal("reload")
                        .requires(EfPermissions.require("explorersfriend.command.reload", 2)).executes(MapCommands::reload))
                .then(Commands.literal("scan")
                        .requires(EfPermissions.require("explorersfriend.command.scan", 2))
                        .then(Commands.literal("status").executes(MapCommands::scanStatus))
                        .then(Commands.literal("mods").executes(MapCommands::scanMods)))
                .then(Commands.literal("colors")
                        .requires(EfPermissions.require("explorersfriend.command.colors", 2))
                        .then(Commands.literal("reload").executes(MapCommands::colorsReload)))
                .then(Commands.literal("cache")
                        .requires(EfPermissions.require("explorersfriend.command.cache", 2))
                        .then(Commands.literal("stats").executes(MapCommands::cacheStats))
                        .then(Commands.literal("prune")
                                .requires(EfPermissions.require("explorersfriend.command.cache.prune", 3))
                                .executes(context -> {
                                    feedback(context, "This deletes all caches. Confirm with: "
                                            + "/efmap cache prune confirm (add 'tiles' to also delete map tiles)");
                                    return 1;
                                })
                                .then(Commands.literal("confirm")
                                        .executes(context -> prune(context, false))
                                        .then(Commands.literal("tiles")
                                                .executes(context -> prune(context, true))))))
                .then(Commands.literal("web")
                        .requires(EfPermissions.require("explorersfriend.command.web", 2))
                        .then(Commands.literal("status").executes(MapCommands::webStatus))
                        .then(Commands.literal("restart").executes(MapCommands::webRestart))));
    }

    // --- handlers -----------------------------------------------------------

    private static int status(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        RenderScheduler.Stats stats = service.scheduler().stats();
        feedback(context, String.format(java.util.Locale.ROOT,
                "Map %s%s | queue: %d job(s)%s | rendered: %d tile(s), %d chunk(s), avg %.1f ms | dirty: %d | web: %s",
                service.isReady() ? "ready" : "starting",
                service.isReadOnly() ? " (READ-ONLY)" : "",
                stats.queued(),
                stats.paused() ? " (PAUSED)" : "",
                stats.tilesRendered(), stats.chunksRendered(), stats.avgTileMillis(),
                service.dirtyTracker().size(),
                service.webAddress() == null ? "off" : service.webAddress()));
        for (Map.Entry<String, int[]> entry : service.fullRenderManager().progressSnapshot().entrySet()) {
            feedback(context, "Full render " + entry.getKey() + ": "
                    + entry.getValue()[0] + "/" + entry.getValue()[1] + " regions");
        }
        return 1;
    }

    private static int render(CommandContext<CommandSourceStack> context, int radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        ServerLevel world = DimensionArgument.getDimension(context, "dimension");
        String slug = TileStore.dimensionSlug(world.dimension().identifier().toString());
        int result = service.startFullRender(slug, radius);
        switch (result) {
            case -1 -> feedback(context, "A full render is already running for this dimension "
                    + "(cancel it first: /efmap cancel).");
            case -2 -> feedback(context, "Service not ready or running read-only.");
            case -3 -> feedback(context, "This dimension is not enabled for the map "
                    + "(see worlds.enabled in the config).");
            case 0 -> feedback(context, "No region files found - nothing to render yet.");
            default -> feedback(context, "Full render started: " + result + " region(s)"
                    + (radius > 0 ? " within " + radius + " blocks of spawn" : "")
                    + ". Progress appears in the console and /efmap status.");
        }
        return Math.max(0, result);
    }

    private static int update(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        ServerLevel world = DimensionArgument.getDimension(context, "dimension");
        String slug = TileStore.dimensionSlug(world.dimension().identifier().toString());
        feedback(context, "Comparing region files against tiles for " + slug + "...");
        service.startUpdateRender(slug, asyncFeedback(context));
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        if (service.scheduler().isPaused()) {
            feedback(context, "Rendering is already paused.");
            return 0;
        }
        service.clearAutoThrottle();
        service.scheduler().pause();
        feedback(context, "Rendering paused. Dirty chunks keep accumulating; resume with /efmap resume.");
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        service.clearAutoThrottle();
        service.scheduler().resume();
        feedback(context, "Rendering resumed.");
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context, ServerLevel worldOrNull) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        String slug = worldOrNull == null ? null
                : TileStore.dimensionSlug(worldOrNull.dimension().identifier().toString());
        int cancelled = service.fullRenderManager().cancel(slug);
        feedback(context, cancelled > 0
                ? "Cancelled " + cancelled + " queued region(s)."
                : "No full render was running" + (slug != null ? " for that dimension" : "") + ".");
        return cancelled;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        MapService service = MapService.get();
        if (service == null) {
            feedback(context, "Map service is not running.");
            return 0;
        }
        feedback(context, service.reload());
        return 1;
    }

    private static int scanStatus(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        ColorPipeline.ColorData data = service.colorData();
        var inventory = data.inventory();
        feedback(context, String.format(java.util.Locale.ROOT,
                "Inventory: %d jar(s) | unchanged: %d | new: %d | changed: %d | removed: %d | duplicates: %d",
                inventory.totalJars(), inventory.unchanged(), inventory.added(),
                inventory.changed(), inventory.removed(), inventory.duplicateContents()));
        feedback(context, "Block colors: " + data.results().size() + " entries"
                + (data.fromCache() ? " (loaded from cache)" : " (freshly scanned)")
                + (data.scanStats() != null
                ? String.format(java.util.Locale.ROOT, ", fallbacks: %d, errors: %d, texture cache hits: %.1f%%",
                data.scanStats().fallbacks(), data.scanStats().errors(),
                data.scanStats().textureCacheHitPercent())
                : ""));
        return 1;
    }

    private static int scanMods(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        var records = service.colorData().inventory().records();
        feedback(context, "Inventoried jar(s): " + records.size() + " (showing up to 20)");
        int shown = 0;
        for (JarRecord record : records) {
            if (shown++ >= 20) {
                feedback(context, "... and " + (records.size() - 20) + " more (see cache/jar-inventory.json)");
                break;
            }
            feedback(context, "- " + record.modId() + " " + record.version() + " ["
                    + record.sha256().substring(0, 12) + "] " + record.fileName());
        }
        return 1;
    }

    private static int colorsReload(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        feedback(context, service.reloadColors());
        return 1;
    }

    private static int cacheStats(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        var source = context.getSource();
        var server = source.getServer();
        // Directory sizes are IO: compute off-thread, report back on the server thread.
        Thread counter = new Thread(() -> {
            long[] tilesSize = {0};
            long[] tileCount = {0};
            long[] cacheSize = {0};
            try (var stream = java.nio.file.Files.walk(service.dataDir())) {
                stream.filter(java.nio.file.Files::isRegularFile).forEach(path -> {
                    try {
                        long size = java.nio.file.Files.size(path);
                        if (path.toString().contains("tiles")) {
                            tilesSize[0] += size;
                            tileCount[0]++;
                        } else {
                            cacheSize[0] += size;
                        }
                    } catch (java.io.IOException ignored) {
                        // racing with writers is fine for stats
                    }
                });
            } catch (java.io.IOException ignored) {
                // partial stats are fine
            }
            String message = String.format(java.util.Locale.ROOT,
                    "Tiles: %d file(s), %.1f MB | Caches: %.1f MB | Data dir: %s",
                    tileCount[0], tilesSize[0] / 1048576.0, cacheSize[0] / 1048576.0, service.dataDir());
            server.execute(() -> source.sendSuccess(() -> Component.literal(message), false));
        }, "EF-CacheStats");
        counter.setDaemon(true);
        counter.start();
        feedback(context, "Measuring cache sizes...");
        return 1;
    }

    private static int prune(CommandContext<CommandSourceStack> context, boolean alsoTiles) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        feedback(context, service.pruneCaches(alsoTiles));
        return 1;
    }

    private static int webStatus(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        String address = service.webAddress();
        if (address == null) {
            feedback(context, "Web server: disabled or failed to start (see console).");
            return 0;
        }
        String baseUrl = service.config().web().publicBaseUrl();
        feedback(context, "Web server: listening on " + address
                + (baseUrl.isBlank() ? "" : " | public URL: " + baseUrl));
        for (DimensionInfo dim : service.dimensionInfos().values()) {
            feedback(context, "- " + dim.id() + " -> /#" + dim.slug());
        }
        return 1;
    }

    private static int webRestart(CommandContext<CommandSourceStack> context) {
        MapService service = ready(context);
        if (service == null) {
            return 0;
        }
        service.restartWebServer(asyncFeedback(context));
        return 1;
    }

    // --- helpers ------------------------------------------------------------

    private static MapService ready(CommandContext<CommandSourceStack> context) {
        MapService service = MapService.get();
        if (service == null) {
            feedback(context, "Map service is not running.");
            return null;
        }
        if (!service.isReady()) {
            feedback(context, "The map is still starting (scanning mod resources) - try again shortly.");
            return null;
        }
        return service;
    }

    private static void feedback(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    /** Thread-safe feedback for async workers: re-dispatches onto the server thread. */
    private static Consumer<String> asyncFeedback(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var server = source.getServer();
        return message -> server.execute(() -> source.sendSuccess(() -> Component.literal(message), false));
    }
}
