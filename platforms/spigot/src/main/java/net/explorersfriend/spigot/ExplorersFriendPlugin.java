package net.explorersfriend.spigot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.config.ConfigIO;
import net.explorersfriend.claims.ClaimManager;
import net.explorersfriend.claims.ClaimProvider;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.marker.CustomIconStore;
import net.explorersfriend.marker.MarkerStore;
import net.explorersfriend.overlay.OverlayLayer;
import net.explorersfriend.marker.IconLibrary;
import net.explorersfriend.marker.MapMarker;
import net.explorersfriend.web.OverlayWebService;
import net.explorersfriend.world.DirtyTracker;
import net.explorersfriend.platform.PlatformInfo;
import net.explorersfriend.region.RegionChunkExtractor;
import net.explorersfriend.render.DimensionContext;
import net.explorersfriend.render.FullRenderManager;
import net.explorersfriend.render.RenderScheduler;
import net.explorersfriend.render.RenderedChunksIndex;
import net.explorersfriend.render.TileRenderer;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.util.NamedThreadFactory;
import net.explorersfriend.util.Log;
import net.explorersfriend.web.MapHttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Locale;
import java.util.Map;

/**
 * Spigot/Paper backend (milestone 1: platform bootstrap, web server, worlds,
 * status; rendering/claims/markers land in the follow-up milestones documented in
 * MULTIPLATFORM.md). Uses only the version-stable Bukkit API - no NMS, no mixins;
 * the same jar targets every supported Minecraft version (api-version 1.21).
 */
public final class ExplorersFriendPlugin extends JavaPlugin {

    private record BukkitDimension(String id, String slug, int spawnX, int spawnZ) {
    }

    private MapConfig config;
    private Path dataDir;
    private TileStore tileStore;
    private MapHttpServer webServer;
    private volatile Map<String, BukkitDimension> dimensions = Map.of();
    private volatile byte[] worldsJson = "{\"worlds\":[]}".getBytes(StandardCharsets.UTF_8);
    private final long startNanos = System.nanoTime();
    private Path cacheDir;
    private volatile Map<String, DimensionContext> contexts = Map.of();
    private RenderedChunksIndex renderedIndex;
    private RenderScheduler scheduler;
    private FullRenderManager fullRender;
    private ExecutorService scanPool;
    private ScheduledExecutorService retryExecutor;
    private volatile boolean ready;
    private OverlayLayer<MapClaim> claimsLayer;
    private OverlayLayer<MarkerStore.Item> markersLayer;
    private MarkerStore markerStore;
    private CustomIconStore customIconStore;
    private ClaimManager claimManager;
    private SpigotPlayerService playerService;
    private final DirtyTracker dirtyTracker = new DirtyTracker();
    private volatile BukkitChunkExtractor liveExtractor;

    @Override
    public void onEnable() {
        String mcVersion = Bukkit.getBukkitVersion().split("-", 2)[0];
        String serverName = Bukkit.getServer().getName();   // "CraftBukkit" on Spigot, "Paper" on Paper
        String platformId = serverName.toLowerCase(Locale.ROOT).contains("paper") ? "paper" : "spigot";
        PlatformInfo.set(new PlatformInfo(platformId, mcVersion, "spigot", getDescription().getVersion()));

        Path configDir = getDataFolder().toPath();
        config = ConfigIO.loadOrCreate(configDir.resolve("config.jsonc"));
        dataDir = Bukkit.getWorldContainer().toPath().resolve(config.storage().dataDir());
        tileStore = new TileStore(dataDir.resolve("tiles"));

        cacheDir = dataDir.resolve("cache");
        refreshDimensions();
        detectIntegrations();
        startWebServer();
        scanPool = Executors.newFixedThreadPool(Math.max(1, config.scan().threads()),
                new NamedThreadFactory("EF-Scan"));
        retryExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("EF-Retry"));
        startOverlays();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::startPipelineAsync);
        Bukkit.getScheduler().runTaskTimer(this, this::onTick, 20L, 1L);
        Log.LOGGER.info("[ExplorersFriend/Init] Spigot backend milestone 1 ready - {} world(s), web {}",
                dimensions.size(), webServer == null ? "disabled" : "on port " + config.web().port());
    }

    @Override
    public void onDisable() {
        if (fullRender != null) {
            fullRender.persistAll();
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (renderedIndex != null) {
            renderedIndex.saveIfDirty();
        }
        if (scanPool != null) {
            scanPool.shutdownNow();
        }
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
        if (markerStore != null) {
            markerStore.saveIfDirty();
        }
        if (webServer != null) {
            webServer.close();
            webServer = null;
        }
        Log.LOGGER.info("[ExplorersFriend/Init] Shutdown complete");
    }

    // --- worlds -------------------------------------------------------------

    private static String dimensionId(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            // the main world maps to the vanilla overworld id so tiles/markers stay
            // compatible across platforms; additional custom worlds keep their name
            default -> world.equals(Bukkit.getWorlds().get(0))
                    ? "minecraft:overworld"
                    : "minecraft:" + world.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
        };
    }

    private void refreshDimensions() {
        Map<String, BukkitDimension> found = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            String id = dimensionId(world);
            String slug = TileStore.dimensionSlug(id);
            found.put(slug, new BukkitDimension(id, slug,
                    world.getSpawnLocation().getBlockX(), world.getSpawnLocation().getBlockZ()));
        }
        dimensions = Map.copyOf(found);
        JsonObject root = new JsonObject();
        root.addProperty("title", config.web().title());
        root.addProperty("playersIntervalSeconds", config.players().updateIntervalSeconds());
        JsonArray worlds = new JsonArray();
        for (BukkitDimension dim : found.values()) {
            JsonObject world = new JsonObject();
            world.addProperty("id", dim.id());
            world.addProperty("slug", dim.slug());
            world.addProperty("zoomLevels", config.render().zoomLevels());
            world.addProperty("spawnX", dim.spawnX());
            world.addProperty("spawnZ", dim.spawnZ());
            worlds.add(world);
        }
        root.add("worlds", worlds);
        worldsJson = root.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- integrations -------------------------------------------------------

    private void detectIntegrations() {
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) {
            Log.LOGGER.info("[ExplorersFriend/Claims] GriefPrevention detected "
                    + "(adapter lands in milestone 3)");
        } else {
            Log.LOGGER.info("[ExplorersFriend/Claims] GriefPrevention: not installed");
        }
        Log.LOGGER.info("[ExplorersFriend/Claims] FTB Chunks / Open Parties and Claims: "
                + "not applicable on this platform (mod-loader mods)");
    }

    // --- web ----------------------------------------------------------------

    private void startWebServer() {
        if (!config.web().enabled()) {
            return;
        }
        try {
            webServer = new MapHttpServer(config.web().bind(), config.web().port(),
                    config.web().threads(), config.web().connectionLimit(),
                    config.web().idleTimeoutSeconds(), config.web().gzip(),
                    new WebData(), getDescription().getVersion());
        } catch (IOException e) {
            Log.LOGGER.error("[ExplorersFriend/Web] Could not start the web server on {}:{} ({})",
                    config.web().bind(), config.web().port(), e.toString());
        }
    }

    private final class WebData implements MapHttpServer.DataSource {

        @Override
        public byte[] playersJson() {
            return "{\"players\":[]}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] statusJson() {
            JsonObject root = new JsonObject();
            root.addProperty("name", "The Explorer's Friend");
            root.addProperty("platform", PlatformInfo.get().platformId());
            root.addProperty("minecraftVersion", PlatformInfo.get().minecraftVersion());
            root.addProperty("ready", true);
            root.addProperty("readOnly", false);
            root.addProperty("uptimeSeconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] worldsJson() {
            return worldsJson;
        }

        @Override
        public Path tileFile(String dimensionSlug, int zoom, int tileX, int tileZ) {
            if (tileStore == null || !dimensions.containsKey(dimensionSlug)
                    || zoom < 0 || zoom > config.render().zoomLevels()) {
                return null;
            }
            return tileStore.tilePath(dimensionSlug, zoom, tileX, tileZ);
        }
    }


    // --- overlays (markers, claims, players, /api/v1) -----------------------

    private void startOverlays() {
        customIconStore = new CustomIconStore(getDataFolder().toPath().resolve("icons"));
        customIconStore.reload(config.markers().customIcons());

        markersLayer = new OverlayLayer<>("markers");
        markerStore = new MarkerStore(dataDir.resolve("markers.json"), markersLayer, config.markers());
        markerStore.load();
        Bukkit.getScheduler().runTaskTimer(this,
                () -> markerStore.saveIfDirty(),
                config.markers().saveIntervalSeconds() * 20L,
                config.markers().saveIntervalSeconds() * 20L);

        claimsLayer = new OverlayLayer<>("claims");
        List<ClaimProvider> providers = new ArrayList<>();
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null
                && providerEnabled("griefprevention")) {
            providers.add(new GriefPreventionClaimProvider(this, ExplorersFriendPlugin::dimensionId));
            Log.LOGGER.info("[ExplorersFriend/Claims] GriefPrevention: adapter active");
        }
        if (!providers.isEmpty() && config.claims().enabled()) {
            claimManager = new ClaimManager(claimsLayer, providers, config.claims(),
                    task -> Bukkit.getScheduler().runTask(this, task), scanPool,
                    TileStore::dimensionSlug);
            claimManager.start(retryExecutor);
        }

        if (config.players().show()) {
            playerService = new SpigotPlayerService(config.players(), ExplorersFriendPlugin::dimensionId);
            long interval = Math.max(1, config.players().updateIntervalSeconds()) * 20L;
            Bukkit.getScheduler().runTaskTimer(this, playerService::sample, interval, interval);
        }

        if (webServer != null) {
            webServer.setOverlayService(new OverlayWebService(config,
                    () -> dimensions.keySet(),
                    claimsLayer, markersLayer,
                    playerService == null ? null : playerService.layer(),
                    uuid -> playerService == null ? new byte[0] : playerService.headPng(uuid),
                    hash -> null,
                    () -> worldsJson,
                    () -> new WebData().statusJson(),
                    () -> "",
                    claimManager == null ? List.of() : claimManager.providerIds(),
                    customIconStore,
                    null));
        }
    }

    private boolean providerEnabled(String id) {
        List<String> enabled = config.claims().enabledProviders();
        return enabled.isEmpty() || enabled.contains("*") || enabled.contains(id);
    }

    // --- render pipeline ----------------------------------------------------

    private void startPipelineAsync() {
        try {
            Path serverJar = serverJarPath();
            List<String> blockIds = new ArrayList<>();
            for (Material material : Material.values()) {
                if (material.isBlock() && !material.isLegacy()) {
                    blockIds.add(material.getKey().toString());
                }
            }
            blockIds.sort(java.util.Comparator.naturalOrder());
            serverJar = unwrapBundler(serverJar, cacheDir);
            SpigotColorPipeline.ColorData colorData = SpigotColorPipeline.run(
                    serverJar, getDataFolder().toPath().getParent(), blockIds,
                    PlatformInfo.get().minecraftVersion(), config, cacheDir,
                    getDataFolder().toPath().resolve("block-colors.jsonc"), scanPool);

            Map<String, DimensionContext> built = new LinkedHashMap<>();
            for (World world : Bukkit.getWorlds()) {
                String id = dimensionId(world);
                String slug = TileStore.dimensionSlug(id);
                boolean hasCeiling = world.getEnvironment() == World.Environment.NETHER;
                RegionChunkExtractor extractor = new RegionChunkExtractor(colorData.palette(),
                        new RegionChunkExtractor.Settings(config.render().waterDepthShading(), hasCeiling));
                built.put(slug, new DimensionContext(id, slug, regionDir(world), extractor));
            }
            contexts = built;

            renderedIndex = RenderedChunksIndex.load(cacheDir.resolve("rendered-chunks.json"));
            TileRenderer renderer = new TileRenderer(config.render().heightShading());
            scheduler = new RenderScheduler(tileStore, renderer, renderedIndex,
                    slug -> contexts.get(slug), retryExecutor,
                    config.render().workers(), config.render().maxQueuedTiles(),
                    config.render().zoomLevels());
            fullRender = new FullRenderManager(scheduler, cacheDir.resolve("render-progress"),
                    config.render().maxQueuedTiles(), config.logging().progressIntervalSeconds());
            liveExtractor = new BukkitChunkExtractor(colorData.palette(),
                    config.render().waterDepthShading());
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.getPluginManager().registerEvents(new BlockChangeListener(), this));
            Bukkit.getScheduler().runTaskTimer(this, this::drainDirtyChunks, 40L, 40L);
            for (DimensionContext context : contexts.values()) {
                fullRender.resumeIfPersisted(context);
            }
            ready = true;
            Log.LOGGER.info("[ExplorersFriend/Init] Ready in {} ms - {} dimension(s): {}",
                    (System.nanoTime() - startNanos) / 1_000_000, contexts.size(),
                    String.join(", ", contexts.keySet()));
        } catch (Exception e) {
            Log.LOGGER.error("[ExplorersFriend/Init] Startup pipeline failed", e);
        }
    }


    /**
     * Since 1.18 the vanilla server jar is a "bundler": the real game jar (with the
     * worldgen data we need) sits inside META-INF/versions/. Extract it once into
     * the cache and use it as the data source; plain jars pass through unchanged.
     */
    private static Path unwrapBundler(Path jar, Path cacheDir) {
        if (jar == null) {
            return null;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            if (zip.getEntry("data/minecraft/worldgen/biome/plains.json") != null) {
                return jar;
            }
            java.util.zip.ZipEntry inner = null;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/versions/") && name.endsWith(".jar")) {
                    inner = entry;
                    break;
                }
            }
            if (inner == null) {
                return jar;
            }
            Path target = cacheDir.resolve("server-data-"
                    + inner.getName().substring(inner.getName().lastIndexOf('/') + 1));
            if (!java.nio.file.Files.isRegularFile(target)
                    || java.nio.file.Files.size(target) != inner.getSize()) {
                java.nio.file.Files.createDirectories(cacheDir);
                try (var in = zip.getInputStream(inner)) {
                    java.nio.file.Files.copy(in, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Log.LOGGER.info("[ExplorersFriend/Scanner] Extracted bundled server jar "
                        + "for worldgen data ({})", target.getFileName());
            }
            return target;
        } catch (java.io.IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Scanner] Could not inspect server jar bundle: {}",
                    e.toString());
            return jar;
        }
    }

    private void onTick() {
        FullRenderManager render = fullRender;
        if (render != null) {
            render.feed();
        }
    }

    private static Path serverJarPath() {
        // Paperclip keeps the unmodified vanilla server jar (with worldgen data)
        // under cache/mojang_<version>.jar; prefer it, fall back to the code source.
        try {
            Path cache = Path.of("cache");
            if (java.nio.file.Files.isDirectory(cache)) {
                try (var stream = java.nio.file.Files.list(cache)) {
                    var mojang = stream
                            .filter(f -> f.getFileName().toString().startsWith("mojang_")
                                    && f.getFileName().toString().endsWith(".jar"))
                            .findFirst();
                    if (mojang.isPresent()) {
                        return mojang.get();
                    }
                }
            }
        } catch (Exception e) {
            Log.LOGGER.debug("[ExplorersFriend/Scanner] No paperclip cache jar: {}", e.toString());
        }
        try {
            return Path.of(Bukkit.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            Log.LOGGER.warn("[ExplorersFriend/Scanner] Cannot locate the server jar: {}", e.toString());
            return null;
        }
    }

    private static Path regionDir(World world) {
        Path folder = world.getWorldFolder().toPath();
        return switch (world.getEnvironment()) {
            case NETHER -> folder.resolve("DIM-1").resolve("region");
            case THE_END -> folder.resolve("DIM1").resolve("region");
            default -> folder.resolve("region");
        };
    }


    // --- live block updates -------------------------------------------------

    /** Marks chunks dirty on common world-change events; snapshots happen later. */
    private final class BlockChangeListener implements Listener {

        private void mark(org.bukkit.block.Block block) {
            String slug = TileStore.dimensionSlug(dimensionId(block.getWorld()));
            dirtyTracker.markDirty(slug, block.getX() >> 4, block.getZ() >> 4);
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
            mark(event.getBlock());
        }

        @EventHandler(ignoreCancelled = true)
        public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
            mark(event.getBlock());
        }

        @EventHandler(ignoreCancelled = true)
        public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
            mark(event.getBlock());
        }

        @EventHandler(ignoreCancelled = true)
        public void onGrow(org.bukkit.event.block.BlockGrowEvent event) {
            mark(event.getBlock());
        }

        @EventHandler(ignoreCancelled = true)
        public void onFade(org.bukkit.event.block.BlockFadeEvent event) {
            mark(event.getBlock());
        }

        @EventHandler(ignoreCancelled = true)
        public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
            event.blockList().forEach(this::mark);
        }

        @EventHandler(ignoreCancelled = true)
        public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
            event.blockList().forEach(this::mark);
        }

        @EventHandler(ignoreCancelled = true)
        public void onLiquid(org.bukkit.event.block.BlockFromToEvent event) {
            mark(event.getToBlock());
        }
    }

    /** Main-thread: snapshot ready dirty chunks; extraction + patch run on workers. */
    private void drainDirtyChunks() {
        BukkitChunkExtractor extractor = liveExtractor;
        RenderScheduler sched = scheduler;
        if (extractor == null || sched == null) {
            return;
        }
        List<DirtyTracker.DirtyChunk> ready = dirtyTracker.drainReady(3000, 15000,
                Math.max(1, config.render().maxSnapshotsPerTick()) * 4);
        for (DirtyTracker.DirtyChunk dirty : ready) {
            World world = null;
            for (World candidate : Bukkit.getWorlds()) {
                if (TileStore.dimensionSlug(dimensionId(candidate)).equals(dirty.dimensionSlug())) {
                    world = candidate;
                    break;
                }
            }
            if (world == null || !world.isChunkLoaded(dirty.chunkX(), dirty.chunkZ())) {
                continue;
            }
            org.bukkit.ChunkSnapshot snapshot = world.getChunkAt(dirty.chunkX(), dirty.chunkZ())
                    .getChunkSnapshot(true, true, false);
            int minY = world.getMinHeight();
            String slug = dirty.dimensionSlug();
            scanPool.submit(() -> {
                try {
                    sched.submitChunkPatch(slug, extractor.extract(snapshot, minY));
                } catch (Exception e) {
                    Log.LOGGER.debug("[ExplorersFriend/Renderer] Live patch failed: {}", e.toString());
                }
            });
        }
    }

    // --- commands -----------------------------------------------------------

    private String pauseResume(boolean pause) {
        if (pause) {
            scheduler.pause();
            return "Rendering paused.";
        }
        scheduler.resume();
        return "Rendering resumed.";
    }


    private boolean markerCommand(CommandSender sender, String[] args) {
        if (markerStore == null || args.length < 2) {
            sender.sendMessage("Usage: /efmap marker <add <name> [icon] | list | remove <name>>");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can add markers at their position.");
                    return true;
                }
                boolean allowed = config.markers().allowPlayerCreation()
                        || sender.hasPermission("explorersfriend.command.marker.admin");
                if (!allowed || !sender.hasPermission("explorersfriend.command.marker")) {
                    sender.sendMessage("You may not create markers on this server.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("Usage: /efmap marker add <name> [icon]");
                    return true;
                }
                String name = args[2];
                String icon = args.length > 3 ? args[3] : IconLibrary.FALLBACK;
                String slug = TileStore.dimensionSlug(dimensionId(player.getWorld()));
                long now = System.currentTimeMillis();
                MapMarker marker = new MapMarker(java.util.UUID.randomUUID().toString(), slug, name,
                        IconLibrary.validateOrFallback(icon),
                        player.getLocation().getBlockX(), player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ(),
                        null, null, null, player.getUniqueId(), player.getName(),
                        now, now, true, MapMarker.SOURCE_COMMAND, null);
                String error = markerStore.add(marker);
                sender.sendMessage(error != null ? error : "Marker '" + name + "' created.");
                return true;
            }
            case "list" -> {
                var items = markerStore.all();
                sender.sendMessage(items.isEmpty() ? "No markers."
                        : items.stream().limit(20).map(m -> m.name() + " (" + m.dimensionSlug()
                                + " " + m.x() + "," + m.y() + "," + m.z() + ")")
                                .reduce((a, b) -> a + "; " + b).orElse(""));
                return true;
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /efmap marker remove <name>");
                    return true;
                }
                var resolved = markerStore.resolve(args[2]);
                if (resolved.isEmpty()) {
                    sender.sendMessage("No unique marker named '" + args[2] + "'.");
                    return true;
                }
                MapMarker marker = resolved.get();
                boolean own = sender instanceof Player player
                        && player.getUniqueId().equals(marker.creator());
                if (!own && !sender.hasPermission("explorersfriend.command.marker.admin")) {
                    sender.sendMessage("You may only remove your own markers.");
                    return true;
                }
                markerStore.remove(marker.id());
                sender.sendMessage("Marker '" + marker.name() + "' removed.");
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /efmap marker <add|list|remove>");
                return true;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("The Explorer's Friend " + getDescription().getVersion()
                    + " on " + PlatformInfo.get().platformId());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (!sender.hasPermission("explorersfriend.command.status")) {
                    sender.sendMessage("Missing permission explorersfriend.command.status");
                    return true;
                }
                sender.sendMessage("Platform: " + PlatformInfo.get().platformId()
                        + " | Minecraft " + PlatformInfo.get().minecraftVersion()
                        + " | worlds: " + dimensions.size()
                        + " | web: " + (webServer == null ? "off" : config.web().bind() + ":" + config.web().port()));
                return true;
            }
            case "web" -> {
                sender.sendMessage(webServer == null ? "Web server is not running."
                        : "Web server on " + config.web().bind() + ":" + config.web().port());
                return true;
            }
            case "render" -> {
                if (!sender.hasPermission("explorersfriend.command.render")) {
                    sender.sendMessage("Missing permission explorersfriend.command.render");
                    return true;
                }
                if (!ready || fullRender == null) {
                    sender.sendMessage("The map service is still starting.");
                    return true;
                }
                String slug = args.length > 1 ? args[1] : contexts.keySet().stream().findFirst().orElse(null);
                DimensionContext context = slug == null ? null : contexts.get(slug);
                if (context == null) {
                    sender.sendMessage("Unknown world. Known: " + String.join(", ", contexts.keySet()));
                    return true;
                }
                World world = Bukkit.getWorlds().stream()
                        .filter(w -> TileStore.dimensionSlug(dimensionId(w)).equals(context.slug()))
                        .findFirst().orElse(null);
                int centerX = world == null ? 0 : world.getSpawnLocation().getBlockX();
                int centerZ = world == null ? 0 : world.getSpawnLocation().getBlockZ();
                int radius = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                int tiles = fullRender.start(context, centerX, centerZ, radius);
                sender.sendMessage("Full render of " + context.slug() + " started (" + tiles + " region tile(s) queued).");
                return true;
            }
            case "pause" -> {
                sender.sendMessage(scheduler != null ? pauseResume(true) : "Not ready.");
                return true;
            }
            case "resume" -> {
                sender.sendMessage(scheduler != null ? pauseResume(false) : "Not ready.");
                return true;
            }
            case "cancel" -> {
                if (fullRender == null) {
                    sender.sendMessage("Not ready.");
                    return true;
                }
                int cancelled = fullRender.cancel(args.length > 1 ? args[1] : null);
                sender.sendMessage(cancelled + " queued tile(s) cancelled.");
                return true;
            }
            case "marker" -> {
                return markerCommand(sender, args);
            }
            default -> {
                sender.sendMessage("Available: /efmap status | render [world] [radius] | pause | resume "
                        + "| cancel [world] | web | marker <add|list|remove>");
                return true;
            }
        }
    }
}
