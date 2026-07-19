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
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

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

    private static final SuggestionProvider<CommandSourceStack> MARKER_SUGGESTIONS = (context, builder) -> {
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

    private static final SuggestionProvider<CommandSourceStack> ICON_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(IconLibrary.ICONS, builder);

    public static void register() {
        CommandRegistrationCallback.EVENT.register(MarkerCommands::registerTree);
    }

    private static void registerTree(CommandDispatcher<CommandSourceStack> dispatcher,
                                     net.minecraft.commands.CommandBuildContext registryAccess,
                                     Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("efmap")
                .then(Commands.literal("marker")
                        .then(Commands.literal("add")
                                .requires(MarkerCommands::mayCreate)
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(context -> add(context, IconLibrary.FALLBACK))
                                        .then(Commands.argument("icon", StringArgumentType.word())
                                                .suggests(ICON_SUGGESTIONS)
                                                .executes(context -> add(context,
                                                        StringArgumentType.getString(context, "icon"))))))
                        .then(Commands.literal("add-at")
                                .requires(source -> Perms.atLeast(source, 2))
                                .then(Commands.argument("dimension",
                                                net.minecraft.commands.arguments.DimensionArgument.dimension())
                                        .then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
                                                        .then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                                .then(Commands.argument("name", StringArgumentType.string())
                                                                        .executes(context -> addAt(context, IconLibrary.FALLBACK))
                                                                        .then(Commands.argument("icon", StringArgumentType.word())
                                                                                .suggests(ICON_SUGGESTIONS)
                                                                                .executes(context -> addAt(context,
                                                                                        StringArgumentType.getString(context, "icon"))))))))))
                        .then(Commands.literal("list").executes(MarkerCommands::list))
                        .then(Commands.literal("info")
                                .then(markerArg().executes(MarkerCommands::info)))
                        .then(Commands.literal("remove")
                                .then(markerArg().executes(MarkerCommands::remove)))
                        .then(Commands.literal("rename")
                                .then(markerArg().then(Commands.argument("newName", StringArgumentType.string())
                                        .executes(MarkerCommands::rename))))
                        .then(Commands.literal("icon")
                                .then(markerArg().then(Commands.argument("icon", StringArgumentType.word())
                                        .suggests(ICON_SUGGESTIONS)
                                        .executes(MarkerCommands::changeIcon))))
                        .then(Commands.literal("move")
                                .then(markerArg().executes(MarkerCommands::move)))
                        .then(Commands.literal("hide")
                                .then(markerArg().executes(context -> setVisible(context, false))))
                        .then(Commands.literal("show")
                                .then(markerArg().executes(context -> setVisible(context, true))))
                        .then(Commands.literal("description")
                                .then(markerArg().then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(MarkerCommands::describe))))
                        .then(Commands.literal("category")
                                .then(markerArg().then(Commands.argument("category", StringArgumentType.word())
                                        .executes(MarkerCommands::categorize))))
                        .then(Commands.literal("categories").executes(MarkerCommands::categories))
                        .then(Commands.literal("icons").executes(MarkerCommands::icons))
                        .then(Commands.literal("teleport")
                                .requires(source -> Perms.atLeast(source, 2))
                                .then(markerArg().executes(MarkerCommands::teleport)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> markerArg() {
        return Commands.argument("marker", StringArgumentType.string()).suggests(MARKER_SUGGESTIONS);
    }

    // --- helpers ------------------------------------------------------------

    private static boolean mayCreate(CommandSourceStack source) {
        MapService service = MapService.get();
        boolean playerAllowed = service != null && service.config().markers().allowPlayerCreation();
        return playerAllowed || Perms.atLeast(source, 2);
    }

    private static MarkerStore store() {
        MapService service = MapService.get();
        return service == null ? null : service.markerStore();
    }

    private static MarkerStore requireStore(CommandContext<CommandSourceStack> context) {
        MarkerStore store = store();
        if (store == null) {
            feedback(context, "Markers are disabled or the map service is still starting.");
        }
        return store;
    }

    private static Optional<MapMarker> resolve(CommandContext<CommandSourceStack> context, MarkerStore store) {
        String reference = StringArgumentType.getString(context, "marker");
        Optional<MapMarker> marker = store.resolve(reference);
        if (marker.isEmpty()) {
            feedback(context, "No unique marker named '" + reference
                    + "' found. Use /efmap marker list or the marker id.");
        }
        return marker;
    }

    private static boolean mayEdit(CommandContext<CommandSourceStack> context, MapMarker marker) {
        if (Perms.atLeast(context.getSource(), 2)) {
            return true;
        }
        if (marker.isBanner()) {
            feedback(context, "Banner markers are managed by placing/breaking the banner "
                    + "(or by an admin).");
            return false;
        }
        ServerPlayer player = context.getSource().getPlayer();
        UUID uuid = player == null ? null : player.getUUID();
        if (uuid == null || !uuid.equals(marker.creator())) {
            feedback(context, "You can only modify your own markers.");
            return false;
        }
        return true;
    }

    private static void feedback(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    // --- handlers -----------------------------------------------------------

    private static int add(CommandContext<CommandSourceStack> context, String icon) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            feedback(context, "Console: use /efmap marker add-at <dimension> <x> <y> <z> <name> [icon].");
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        if (!IconLibrary.isKnown(icon)) {
            feedback(context, "Unknown icon '" + icon + "' - using '" + IconLibrary.FALLBACK
                    + "'. See /efmap marker icons.");
        }
        String slug = TileStore.dimensionSlug(player.level().dimension().identifier().toString());
        long now = System.currentTimeMillis();
        MapMarker marker = new MapMarker(UUID.randomUUID().toString(), slug, name,
                IconLibrary.validateOrFallback(icon),
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                null, null, null, player.getUUID(), player.getGameProfile().name(),
                now, now, true, MapMarker.SOURCE_COMMAND, null);
        String error = store.add(marker);
        feedback(context, error != null ? error
                : "Marker '" + name + "' created at " + marker.x() + " " + marker.y() + " " + marker.z() + ".");
        return error == null ? 1 : 0;
    }

    private static int addAt(CommandContext<CommandSourceStack> context, String icon)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        var world = net.minecraft.commands.arguments.DimensionArgument.getDimension(context, "dimension");
        String slug = TileStore.dimensionSlug(world.dimension().identifier().toString());
        String name = StringArgumentType.getString(context, "name");
        long now = System.currentTimeMillis();
        ServerPlayer player = context.getSource().getPlayer();
        MapMarker marker = new MapMarker(UUID.randomUUID().toString(), slug, name,
                IconLibrary.validateOrFallback(icon),
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"),
                null, null, null,
                player == null ? null : player.getUUID(),
                player == null ? "Console" : player.getGameProfile().name(),
                now, now, true, MapMarker.SOURCE_COMMAND, null);
        String error = store.add(marker);
        feedback(context, error != null ? error : "Marker '" + name + "' created in " + slug + ".");
        return error == null ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
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

    private static int info(CommandContext<CommandSourceStack> context) {
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

    private static int remove(CommandContext<CommandSourceStack> context) {
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

    private static int rename(CommandContext<CommandSourceStack> context) {
        return mutate(context, "renamed", marker -> withName(marker,
                StringArgumentType.getString(context, "newName")));
    }

    private static int changeIcon(CommandContext<CommandSourceStack> context) {
        String icon = StringArgumentType.getString(context, "icon");
        if (!IconLibrary.isKnown(icon)) {
            feedback(context, "Unknown icon '" + icon + "'. See /efmap marker icons.");
            return 0;
        }
        return mutate(context, "icon changed", marker -> withIcon(marker, icon));
    }

    private static int move(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            feedback(context, "Only players can move a marker to their position.");
            return 0;
        }
        String slug = TileStore.dimensionSlug(player.level().dimension().identifier().toString());
        return mutate(context, "moved to your position", marker -> withPosition(marker, slug,
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ())));
    }

    private static int setVisible(CommandContext<CommandSourceStack> context, boolean visible) {
        return mutate(context, visible ? "shown" : "hidden", marker -> withVisible(marker, visible));
    }

    private static int describe(CommandContext<CommandSourceStack> context) {
        return mutate(context, "description updated", marker -> withDescription(marker,
                StringArgumentType.getString(context, "text")));
    }

    private static int categorize(CommandContext<CommandSourceStack> context) {
        return mutate(context, "category updated", marker -> withCategory(marker,
                StringArgumentType.getString(context, "category").toLowerCase(java.util.Locale.ROOT)));
    }

    private static int categories(CommandContext<CommandSourceStack> context) {
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

    private static int icons(CommandContext<CommandSourceStack> context) {
        feedback(context, "Available icons: " + String.join(", ", IconLibrary.ICONS));
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> context) {
        MarkerStore store = requireStore(context);
        if (store == null) {
            return 0;
        }
        Optional<MapMarker> marker = resolve(context, store);
        ServerPlayer player = context.getSource().getPlayer();
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
        player.teleportTo(target.world(), m.x() + 0.5, m.y() + 1.0, m.z() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        feedback(context, "Teleported to '" + m.name() + "'.");
        return 1;
    }

    private static int mutate(CommandContext<CommandSourceStack> context, String verb,
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
