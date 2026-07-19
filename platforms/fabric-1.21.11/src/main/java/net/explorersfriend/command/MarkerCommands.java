package net.explorersfriend.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.explorersfriend.core.MapService;
import net.explorersfriend.marker.IconLibrary;
import net.explorersfriend.marker.MapMarker;
import net.explorersfriend.marker.MarkerStore;
import net.explorersfriend.render.TileStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@code /efmap marker ...} — player-facing marker management. Registered as a second
 * {@code efmap} root literal; Brigadier merges it into the main tree.
 *
 * <p>Permissions: creating/editing own markers needs level 0 when
 * {@code markers.allow-player-creation} is on (else level 2); editing foreign markers,
 * banner-marker deletion and teleporting need level 2. All handlers return
 * immediately; store mutations are cheap synchronized map operations.</p>
 */
public final class MarkerCommands {

    private MarkerCommands() {
    }

    private static final SuggestionProvider<ServerCommandSource> MARKER_SUGGESTIONS = (context, builder) -> {
        MarkerStore store = store();
        if (store != null) {
            TreeSet<String> names = new TreeSet<>();
            for (MapMarker marker : store.all()) {
                names.add(marker.name().contains(" ") ? "\"" + marker.name() + "\"" : marker.name());
            }
            names.forEach(builder::suggest);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> ICON_SUGGESTIONS = (context, builder) ->
            CommandSource.suggestMatching(IconLibrary.ICONS, builder);

    public static void register() {
        CommandRegistrationCallback.EVENT.register(MarkerCommands::registerTree);
    }

    private static void registerTree(CommandDispatcher<ServerCommandSource> dispatcher,
                                     net.minecraft.command.CommandRegistryAccess registryAccess,
                                     CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("efmap")
                .then(CommandManager.literal("marker")
                        .then(CommandManager.literal("add")
                                .requires(MarkerCommands::mayCreate)
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(context -> add(context, IconLibrary.FALLBACK))
                                        .then(CommandManager.argument("icon", StringArgumentType.word())
                                                .suggests(ICON_SUGGESTIONS)
                                                .executes(context -> add(context,
                                                        StringArgumentType.getString(context, "icon"))))))
                        .then(CommandManager.literal("add-at")
                                .requires(source -> net.explorersfriend.command.Perms.atLeast(source, 2))
                                .then(CommandManager.argument("dimension",
                                                net.minecraft.command.argument.DimensionArgumentType.dimension())
                                        .then(CommandManager.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                .then(CommandManager.argument("y", IntegerArgumentType.integer(-2048, 2048))
                                                        .then(CommandManager.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                                .then(CommandManager.argument("name", StringArgumentType.string())
                                                                        .executes(context -> addAt(context, IconLibrary.FALLBACK))
                                                                        .then(CommandManager.argument("icon", StringArgumentType.word())
                                                                                .suggests(ICON_SUGGESTIONS)
                                                                                .executes(context -> addAt(context,
                                                                                        StringArgumentType.getString(context, "icon"))))))))))
                        .then(CommandManager.literal("list").executes(MarkerCommands::list))
                        .then(CommandManager.literal("info")
                                .then(markerArg().executes(MarkerCommands::info)))
                        .then(CommandManager.literal("remove")
                                .then(markerArg().executes(MarkerCommands::remove)))
                        .then(CommandManager.literal("rename")
                                .then(markerArg().then(CommandManager.argument("newName", StringArgumentType.string())
                                        .executes(MarkerCommands::rename))))
                        .then(CommandManager.literal("icon")
                                .then(markerArg().then(CommandManager.argument("icon", StringArgumentType.word())
                                        .suggests(ICON_SUGGESTIONS)
                                        .executes(MarkerCommands::changeIcon))))
                        .then(CommandManager.literal("move")
                                .then(markerArg().executes(MarkerCommands::move)))
                        .then(CommandManager.literal("hide")
                                .then(markerArg().executes(context -> setVisible(context, false))))
                        .then(CommandManager.literal("show")
                                .then(markerArg().executes(context -> setVisible(context, true))))
                        .then(CommandManager.literal("description")
                                .then(markerArg().then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(MarkerCommands::describe))))
                        .then(CommandManager.literal("category")
                                .then(markerArg().then(CommandManager.argument("category", StringArgumentType.word())
                                        .executes(MarkerCommands::categorize))))
                        .then(CommandManager.literal("categories").executes(MarkerCommands::categories))
                        .then(CommandManager.literal("icons").executes(MarkerCommands::icons))
                        .then(CommandManager.literal("teleport")
                                .requires(source -> net.explorersfriend.command.Perms.atLeast(source, 2))
                                .then(markerArg().executes(MarkerCommands::teleport)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> markerArg() {
        return CommandManager.argument("marker", StringArgumentType.string()).suggests(MARKER_SUGGESTIONS);
    }

    // --- helpers ------------------------------------------------------------

    private static boolean mayCreate(ServerCommandSource source) {
        MapService service = MapService.get();
        boolean playerAllowed = service != null && service.config().markers().allowPlayerCreation();
        return playerAllowed || net.explorersfriend.command.Perms.atLeast(source, 2);
    }

    private static MarkerStore store() {
        MapService service = MapService.get();
        return service == null ? null : service.markerStore();
    }

    private static MarkerStore requireStore(CommandContext<ServerCommandSource> context) {
        MarkerStore store = store();
        if (store == null) {
            feedback(context, "Markers are disabled or the map service is still starting.");
        }
        return store;
    }

    private static Optional<MapMarker> resolve(CommandContext<ServerCommandSource> context, MarkerStore store) {
        String reference = StringArgumentType.getString(context, "marker");
        Optional<MapMarker> marker = store.resolve(reference);
        if (marker.isEmpty()) {
            feedback(context, "No unique marker named '" + reference
                    + "' found. Use /efmap marker list or the marker id.");
        }
        return marker;
    }

    private static boolean mayEdit(CommandContext<ServerCommandSource> context, MapMarker marker) {
        if (net.explorersfriend.command.Perms.atLeast(context.getSource(), 2)) {
            return true;
        }
        if (marker.isBanner()) {
            feedback(context, "Banner markers are managed by placing/breaking the banner "
                    + "(or by an admin).");
            return false;
        }
        ServerPlayerEntity player = context.getSource().getPlayer();
        UUID uuid = player == null ? null : player.getUuid();
        if (uuid == null || !uuid.equals(marker.creator())) {
            feedback(context, "You can only modify your own markers.");
            return false;
        }
        return true;
    }

    private static void feedback(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendFeedback(() -> Text.literal(message), false);
    }

    // --- handlers -----------------------------------------------------------

    private static int add(CommandContext<ServerCommandSource> context, String icon) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            feedback(context, "Console: use /efmap marker add-at <dimension> <x> <y> <z> <name> [icon].");
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        if (!IconLibrary.isKnown(icon)) {
            feedback(context, "Unknown icon '" + icon + "' - using '" + IconLibrary.FALLBACK
                    + "'. See /efmap marker icons.");
        }
        String slug = TileStore.dimensionSlug(player.getEntityWorld().getRegistryKey().getValue().toString());
        long now = System.currentTimeMillis();
        MapMarker marker = new MapMarker(UUID.randomUUID().toString(), slug, name,
                IconLibrary.validateOrFallback(icon),
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                null, null, null, player.getUuid(), player.getGameProfile().name(),
                now, now, true, MapMarker.SOURCE_COMMAND, null);
        String error = store.add(marker);
        feedback(context, error != null ? error
                : "Marker '" + name + "' created at " + marker.x() + " " + marker.y() + " " + marker.z() + ".");
        return error == null ? 1 : 0;
    }

    private static int addAt(CommandContext<ServerCommandSource> context, String icon)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        var world = net.minecraft.command.argument.DimensionArgumentType.getDimensionArgument(context, "dimension");
        String slug = TileStore.dimensionSlug(world.getRegistryKey().getValue().toString());
        String name = StringArgumentType.getString(context, "name");
        long now = System.currentTimeMillis();
        ServerPlayerEntity player = context.getSource().getPlayer();
        MapMarker marker = new MapMarker(UUID.randomUUID().toString(), slug, name,
                IconLibrary.validateOrFallback(icon),
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"),
                null, null, null,
                player == null ? null : player.getUuid(),
                player == null ? "Console" : player.getGameProfile().name(),
                now, now, true, MapMarker.SOURCE_COMMAND, null);
        String error = store.add(marker);
        feedback(context, error != null ? error : "Marker '" + name + "' created in " + slug + ".");
        return error == null ? 1 : 0;
    }

    private static int list(CommandContext<ServerCommandSource> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        List<MapMarker> all = new ArrayList<>(store.all());
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        feedback(context, "Markers: " + all.size() + " (showing up to 20)");
        int shown = 0;
        for (MapMarker marker : all) {
            if (shown++ >= 20) {
                break;
            }
            feedback(context, "- '" + marker.name() + "' [" + marker.icon() + "] "
                    + marker.dimensionSlug() + " " + marker.x() + "," + marker.y() + "," + marker.z()
                    + (marker.isBanner() ? " (banner)" : ""));
        }
        return all.size();
    }

    private static int info(CommandContext<ServerCommandSource> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        Optional<MapMarker> marker = resolve(context, store);
        if (marker.isEmpty()) {
            return 0;
        }
        MapMarker m = marker.get();
        feedback(context, "Marker '" + m.name() + "' (" + m.id() + ")");
        feedback(context, "  " + m.dimensionSlug() + " @ " + m.x() + "," + m.y() + "," + m.z()
                + " | icon: " + m.icon() + " | source: " + m.source()
                + (m.category() != null ? " | category: " + m.category() : ""));
        if (m.creatorName() != null) {
            feedback(context, "  created by " + m.creatorName());
        }
        if (m.description() != null) {
            feedback(context, "  " + m.description());
        }
        return 1;
    }

    private static int remove(CommandContext<ServerCommandSource> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        Optional<MapMarker> marker = resolve(context, store);
        if (marker.isEmpty() || !mayEdit(context, marker.get())) {
            return 0;
        }
        store.remove(marker.get().id());
        feedback(context, "Marker '" + marker.get().name() + "' removed.");
        return 1;
    }

    private static int rename(CommandContext<ServerCommandSource> context) {
        return mutate(context, "renamed", marker -> withName(marker,
                StringArgumentType.getString(context, "newName")));
    }

    private static int changeIcon(CommandContext<ServerCommandSource> context) {
        String icon = StringArgumentType.getString(context, "icon");
        if (!IconLibrary.isKnown(icon)) {
            feedback(context, "Unknown icon '" + icon + "'. See /efmap marker icons.");
            return 0;
        }
        return mutate(context, "icon changed", marker -> withIcon(marker, icon));
    }

    private static int move(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            feedback(context, "Only players can move a marker to their position.");
            return 0;
        }
        String slug = TileStore.dimensionSlug(player.getEntityWorld().getRegistryKey().getValue().toString());
        return mutate(context, "moved to your position", marker -> withPosition(marker, slug,
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ())));
    }

    private static int setVisible(CommandContext<ServerCommandSource> context, boolean visible) {
        return mutate(context, visible ? "shown" : "hidden", marker -> withVisible(marker, visible));
    }

    private static int describe(CommandContext<ServerCommandSource> context) {
        return mutate(context, "description updated", marker -> withDescription(marker,
                StringArgumentType.getString(context, "text")));
    }

    private static int categorize(CommandContext<ServerCommandSource> context) {
        return mutate(context, "category updated", marker -> withCategory(marker,
                StringArgumentType.getString(context, "category").toLowerCase(java.util.Locale.ROOT)));
    }

    private static int categories(CommandContext<ServerCommandSource> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        TreeSet<String> categories = new TreeSet<>();
        for (MapMarker marker : store.all()) {
            if (marker.category() != null) {
                categories.add(marker.category());
            }
        }
        feedback(context, categories.isEmpty() ? "No categories in use."
                : "Categories: " + String.join(", ", categories));
        return categories.size();
    }

    private static int icons(CommandContext<ServerCommandSource> context) {
        feedback(context, "Available icons: " + String.join(", ", IconLibrary.ICONS));
        return 1;
    }

    private static int teleport(CommandContext<ServerCommandSource> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        Optional<MapMarker> marker = resolve(context, store);
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (marker.isEmpty() || player == null) {
            return 0;
        }
        MapService service = MapService.get();
        var target = service.dimensionInfos().get(marker.get().dimensionSlug());
        if (target == null) {
            feedback(context, "The marker's dimension is not available.");
            return 0;
        }
        MapMarker m = marker.get();
        player.teleport(target.world(), m.x() + 0.5, m.y() + 1.0, m.z() + 0.5,
                java.util.Set.of(), player.getYaw(), player.getPitch(), false);
        feedback(context, "Teleported to '" + m.name() + "'.");
        return 1;
    }

    private static int mutate(CommandContext<ServerCommandSource> context, String verb,
                              java.util.function.Function<MapMarker, MapMarker> change) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        Optional<MapMarker> marker = resolve(context, store);
        if (marker.isEmpty() || !mayEdit(context, marker.get())) {
            return 0;
        }
        String error = store.update(marker.get().id(), change);
        feedback(context, error != null ? error : "Marker '" + marker.get().name() + "' " + verb + ".");
        return error == null ? 1 : 0;
    }

    // --- record "with" helpers ----------------------------------------------

    private static MapMarker withName(MapMarker m, String name) {
        return new MapMarker(m.id(), m.dimensionSlug(), name, m.icon(), m.x(), m.y(), m.z(),
                m.description(), m.category(), m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), m.visible(), m.source(), m.bannerDesign());
    }

    private static MapMarker withIcon(MapMarker m, String icon) {
        return new MapMarker(m.id(), m.dimensionSlug(), m.name(), icon, m.x(), m.y(), m.z(),
                m.description(), m.category(), m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), m.visible(), m.source(), m.bannerDesign());
    }

    private static MapMarker withPosition(MapMarker m, String slug, int x, int y, int z) {
        return new MapMarker(m.id(), slug, m.name(), m.icon(), x, y, z,
                m.description(), m.category(), m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), m.visible(), m.source(), m.bannerDesign());
    }

    private static MapMarker withVisible(MapMarker m, boolean visible) {
        return new MapMarker(m.id(), m.dimensionSlug(), m.name(), m.icon(), m.x(), m.y(), m.z(),
                m.description(), m.category(), m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), visible, m.source(), m.bannerDesign());
    }

    private static MapMarker withDescription(MapMarker m, String description) {
        return new MapMarker(m.id(), m.dimensionSlug(), m.name(), m.icon(), m.x(), m.y(), m.z(),
                description, m.category(), m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), m.visible(), m.source(), m.bannerDesign());
    }

    private static MapMarker withCategory(MapMarker m, String category) {
        return new MapMarker(m.id(), m.dimensionSlug(), m.name(), m.icon(), m.x(), m.y(), m.z(),
                m.description(), category, m.colorRgb(), m.creator(), m.creatorName(),
                m.createdAtEpochMs(), System.currentTimeMillis(), m.visible(), m.source(), m.bannerDesign());
    }
}
