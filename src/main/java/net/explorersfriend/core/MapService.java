package net.explorersfriend.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.api.ExplorersFriendApi;
import net.explorersfriend.config.ConfigIO;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.render.DimensionContext;
import net.explorersfriend.render.FullRenderManager;
import net.explorersfriend.render.RenderScheduler;
import net.explorersfriend.render.RenderedChunksIndex;
import net.explorersfriend.render.RuntimePalette;
import net.explorersfriend.render.TileRenderer;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.region.RegionChunkExtractor;
import net.explorersfriend.util.NamedThreadFactory;
import net.explorersfriend.web.MapHttpServer;
import net.explorersfriend.world.BlockChangeHooks;
import net.explorersfriend.world.ChunkSnapshotter;
import net.explorersfriend.world.DirtyTracker;
import net.explorersfriend.world.LiveChunkExtractor;
import net.explorersfriend.claims.ClaimManager;
import net.explorersfriend.claims.ClaimProviders;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.marker.BannerIconRenderer;
import net.explorersfriend.marker.BannerWatcher;
import net.explorersfriend.marker.MarkerStore;
import net.explorersfriend.overlay.OverlayLayer;
import net.explorersfriend.web.OverlayWebService;
import net.explorersfriend.world.LivePlayerService;
import net.explorersfriend.world.SkinService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Per-server-instance container owning every subsystem: configuration, worker pools,
 * the scanner/color pipeline, dirty tracking, the render scheduler, and the web server.
 *
 * <p>Lifecycle: created on SERVER_STARTING (server thread), started asynchronously on
 * SERVER_STARTED (scan pool), shut down on SERVER_STOPPING in the documented order.
 * The {@code ready} flag gates every event handler, so nothing heavy runs before the
 * pipeline finished. Thread-safety: subsystem references are written once before
 * {@code ready=true} (safe publication via volatile), except the color tables which
 * are volatile to support {@code /efmap colors reload}.</p>
 */
public final class MapService {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;
    private static volatile MapService instance;

    private final MinecraftServer server;
    private final long startNanos = System.nanoTime();

    private final Path configDir;
    private final Path dataDir;
    private final Path cacheDir;

    private volatile MapConfig config;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private boolean readOnly;

    private ExecutorService scanPool;
    private ScheduledExecutorService sched;

    private volatile boolean ready;
    private volatile boolean stopping;

    private volatile ColorPipeline.ColorData colorData;
    private volatile Map<String, DimensionInfo> dimensions = Map.of();
    private volatile Map<String, DimensionContext> contexts = Map.of();
    private volatile byte[] worldsJson = "{\"worlds\":[]}".getBytes(StandardCharsets.UTF_8);

    private TileStore tileStore;
    private RenderedChunksIndex renderedIndex;
    private RenderScheduler scheduler;
    private FullRenderManager fullRender;
    private final DirtyTracker dirtyTracker = new DirtyTracker();
    private volatile ChunkSnapshotter snapshotter;
    private MapHttpServer webServer;

    private volatile LivePlayerService livePlayers;
    private SkinService skinService;
    private OverlayLayer<MapClaim> claimsLayer;
    private ClaimManager claimManager;
    private OverlayLayer<MarkerStore.Item> markersLayer;
    private MarkerStore markerStore;
    private volatile BannerWatcher bannerWatcher;
    private BannerIconRenderer bannerIcons;
    private volatile boolean autoThrottled;

    private MapService(MinecraftServer server) {
        this.server = server;
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve("explorersfriend");
        this.config = ConfigIO.loadOrCreate(configDir.resolve("config.jsonc"));
        Path runDir = server.getRunDirectory().toAbsolutePath().normalize();
        this.dataDir = runDir.resolve(config.storage().dataDir()).normalize();
        this.cacheDir = dataDir.resolve("cache");
    }

    // --- static lifecycle --------------------------------------------------

    public static void create(MinecraftServer server) {
        MapService service = new MapService(server);
        instance = service;
        LOGGER.info("[ExplorersFriend/Init] Map service created (data dir: {})", service.dataDir);
    }

    public static void ifPresent(Consumer<MapService> action) {
        MapService current = instance;
        if (current != null) {
            action.accept(current);
        }
    }

    public static MapService get() {
        return instance;
    }

    public static void shutdownCurrent() {
        MapService current = instance;
        instance = null;
        if (current != null) {
            current.shutdown();
        }
    }

    // --- startup -----------------------------------------------------------

    /** SERVER_STARTED (server thread): collect world facts, then continue off-thread. */
    public void startAsync() {
        Map<String, DimensionInfo> dims = collectDimensions();
        this.dimensions = dims;
        if (dims.isEmpty()) {
            LOGGER.warn("[ExplorersFriend/Init] No enabled dimensions - the map stays disabled "
                    + "(check worlds.enabled/disabled in the config)");
            return;
        }
        scanPool = Executors.newFixedThreadPool(config.scan().threads(), new NamedThreadFactory("EF-Scan"));
        sched = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("EF-Sched"));
        String gameVersion = server.getVersion();
        scanPool.submit(() -> {
            try {
                runStartupPipeline(gameVersion);
            } catch (Throwable t) {
                LOGGER.error("[ExplorersFriend/Init] Startup pipeline failed - map disabled for this session", t);
            }
        });
    }

    private void runStartupPipeline(String gameVersion) throws IOException {
        logEnvironment(gameVersion);
        Files.createDirectories(cacheDir);
        acquireLock();
        if (config.storage().pruneCachesOnStart() && !readOnly) {
            pruneCaches(false);
        }

        colorData = ColorPipeline.run(server.getRegistryManager(), gameVersion, config,
                cacheDir, configDir.resolve("block-colors.jsonc"), scanPool);

        tileStore = new TileStore(dataDir.resolve("tiles"));
        renderedIndex = RenderedChunksIndex.load(cacheDir.resolve("rendered-chunks.json"));
        for (DimensionInfo dim : dimensions.values()) {
            if (!tileStore.checkOrWriteMeta(dim.slug(), config.render().zoomLevels()) && !readOnly) {
                LOGGER.info("[ExplorersFriend/Cache] Tile format changed for {}; chunks will re-render", dim.id());
                renderedIndex.clearDimension(dim.slug());
            }
        }

        rebuildContexts();
        TileRenderer renderer = new TileRenderer(config.render().heightShading());
        scheduler = new RenderScheduler(tileStore, renderer, renderedIndex, slug -> contexts.get(slug),
                sched, readOnly ? 1 : config.render().workers(), config.render().maxQueuedTiles(),
                config.render().zoomLevels());
        scheduler.addCompletionListener((key, success) -> {
            if (success) {
                ExplorersFriendApi.fireTileRendered(key.dimensionSlug(), key.zoom(), key.tileX(), key.tileZ());
            }
        });
        fullRender = new FullRenderManager(scheduler, cacheDir.resolve("render-progress"),
                config.render().maxQueuedTiles(), config.logging().progressIntervalSeconds());

        LiveChunkExtractor liveExtractor = new LiveChunkExtractor(colorData.stateTable(),
                colorData.biomeTable(), config.render().waterDepthShading());
        snapshotter = new ChunkSnapshotter(liveExtractor, scheduler, this::slugOfWorld,
                config.render().maxSnapshotsPerTick(), config.render().tickBudgetMicros());

        if (!readOnly) {
            BlockChangeHooks.install(this::onBlockChanged);
            for (DimensionInfo dim : dimensions.values()) {
                fullRender.resumeIfPersisted(contexts.get(dim.slug()));
            }
            if (config.render().fullRenderOnFirstStart()) {
                for (DimensionInfo dim : dimensions.values()) {
                    if (!Files.isDirectory(tileStore.dimensionDir(dim.slug()).resolve("0"))
                            && !fullRender.isActive(dim.slug())) {
                        fullRender.start(contexts.get(dim.slug()), dim.spawnX(), dim.spawnZ(),
                                config.worlds().maxRenderRadiusBlocks());
                    }
                }
            }
        }

        setupOverlays();
        rebuildWorldsJson();
        startWebServer();
        schedulePeriodicTasks();
        ready = true;
        LOGGER.info("[ExplorersFriend/Init] Ready in {} ms{} - {} dimension(s): {}",
                (System.nanoTime() - startNanos) / 1_000_000,
                readOnly ? " (READ-ONLY: another instance holds the cache lock)" : "",
                dimensions.size(), String.join(", ", dimensions.keySet()));
    }

    private void logEnvironment(String gameVersion) {
        FabricLoader loader = FabricLoader.getInstance();
        String loaderVersion = loader.getModContainer("fabricloader")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String fabricApiVersion = loader.getModContainer("fabric-api")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        LOGGER.info("[ExplorersFriend/Init] Minecraft {} | Java {} | Fabric Loader {} | Fabric API {} | {} mod(s)",
                gameVersion, Runtime.version().feature(), loaderVersion, fabricApiVersion,
                loader.getAllMods().size());
    }

    private void acquireLock() {
        try {
            lockChannel = FileChannel.open(cacheDir.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) {
                readOnly = true;
                LOGGER.error("[ExplorersFriend/Cache] Another process holds the cache lock ({}). "
                        + "Running in READ-ONLY mode: the map is served but nothing is scanned or rendered.",
                        cacheDir.resolve(".lock"));
            }
        } catch (IOException e) {
            readOnly = true;
            LOGGER.error("[ExplorersFriend/Cache] Could not acquire cache lock ({}); running READ-ONLY",
                    e.toString());
        }
    }

    private Map<String, DimensionInfo> collectDimensions() {
        Map<String, DimensionInfo> out = new LinkedHashMap<>();
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        for (ServerWorld world : server.getWorlds()) {
            String id = world.getRegistryKey().getValue().toString();
            if (!isDimensionEnabled(id)) {
                continue;
            }
            String slug = TileStore.dimensionSlug(id);
            Path regionDir = DimensionType.getSaveDirectory(world.getRegistryKey(), worldRoot).resolve("region");
            BlockPos spawn = world.getSpawnPos();
            out.put(slug, new DimensionInfo(id, slug, world, regionDir,
                    spawn.getX(), spawn.getZ(), world.getDimension().hasCeiling()));
        }
        return out;
    }

    private boolean isDimensionEnabled(String id) {
        MapConfig.Worlds worlds = config.worlds();
        if (worlds.disabled().contains(id)) {
            return false;
        }
        return worlds.enabled().contains("*") || worlds.enabled().contains(id);
    }

    private void rebuildContexts() {
        ColorPipeline.ColorData data = colorData;
        Map<String, DimensionContext> out = new HashMap<>();
        for (DimensionInfo dim : dimensions.values()) {
            RegionChunkExtractor extractor = new RegionChunkExtractor(data.palette(),
                    new RegionChunkExtractor.Settings(config.render().waterDepthShading(), dim.hasCeiling()));
            out.put(dim.slug(), new DimensionContext(dim.id(), dim.slug(), dim.regionDir(), extractor));
        }
        contexts = out;
    }

    private void startWebServer() {
        if (!config.web().enabled()) {
            LOGGER.info("[ExplorersFriend/Web] Web server disabled by config");
            return;
        }
        String modVersion = FabricLoader.getInstance().getModContainer(ExplorersFriend.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
        try {
            webServer = new MapHttpServer(config.web().bind(), config.web().port(),
                    config.web().threads(), config.web().connectionLimit(),
                    config.web().idleTimeoutSeconds(), config.web().gzip(),
                    new WebData(), modVersion);
            webServer.setOverlayService(new OverlayWebService(config,
                    () -> dimensions.keySet(),
                    claimsLayer, markersLayer,
                    config.players().show() ? livePlayers : null,
                    uuid -> skinService.headPng(uuid),
                    hash -> bannerIcons == null ? null : bannerIcons.pngForHash(hash),
                    () -> worldsJson,
                    this::buildStatusJson,
                    this::buildMetricsText,
                    claimManager == null ? List.of() : claimManager.providerIds()));
        } catch (IOException e) {
            LOGGER.error("[ExplorersFriend/Web] Could not start the web server on {}:{} ({}). "
                    + "The map is unavailable; the game server keeps running.",
                    config.web().bind(), config.web().port(), e.toString());
        }
    }

    /** Players, markers, banners and claims — everything the overlay API serves. */
    private void setupOverlays() {
        livePlayers = new LivePlayerService(config.players());
        skinService = new SkinService(cacheDir.resolve("skins"), config.players(), scanPool);
        if (config.players().show()) {
            LOGGER.info("[ExplorersFriend/Players] Live player layer enabled: interval={}s, privacyDelay={}s",
                    config.players().updateIntervalSeconds(), config.players().positionDelaySeconds());
        }

        if (config.markers().enabled() && !readOnly) {
            markersLayer = new OverlayLayer<>("markers");
            markerStore = new MarkerStore(dataDir.resolve("markers.json"), markersLayer, config.markers());
            bannerIcons = new BannerIconRenderer(colorData.vanillaJar());
            markerStore.setBannerIconHashResolver(bannerIcons::hashFor);
            markerStore.load();
            bannerWatcher = new BannerWatcher(markerStore, config.markers(), this::slugOfWorld);
        }

        if (config.claims().enabled()) {
            claimsLayer = new OverlayLayer<>("claims");
            List<net.explorersfriend.claims.ClaimProvider> providers =
                    ClaimProviders.detect(config.claims(), configDir);
            claimManager = new ClaimManager(claimsLayer, providers, config.claims(),
                    server, scanPool, this::dimensionIdToSlug);
            claimManager.start(server, sched);
        }
    }

    private String dimensionIdToSlug(String dimensionId) {
        String slug = TileStore.dimensionSlug(dimensionId);
        return dimensions.containsKey(slug) ? slug : null;
    }

    /** Prometheus text exposition of the live counters. */
    private String buildMetricsText() {
        StringBuilder out = new StringBuilder(1024);
        appendMetric(out, "ef_uptime_seconds", "gauge",
                Long.toString((System.nanoTime() - startNanos) / 1_000_000_000L));
        RenderScheduler sch = scheduler;
        if (sch != null) {
            RenderScheduler.Stats stats = sch.stats();
            appendMetric(out, "ef_render_queue", "gauge", Long.toString(stats.queued()));
            appendMetric(out, "ef_tiles_rendered_total", "counter", Long.toString(stats.tilesRendered()));
            appendMetric(out, "ef_tiles_failed_total", "counter", Long.toString(stats.tilesFailed()));
            appendMetric(out, "ef_chunks_rendered_total", "counter", Long.toString(stats.chunksRendered()));
            appendMetric(out, "ef_tile_render_millis_avg", "gauge",
                    String.format(java.util.Locale.ROOT, "%.2f", stats.avgTileMillis()));
            appendMetric(out, "ef_render_paused", "gauge", stats.paused() ? "1" : "0");
        }
        appendMetric(out, "ef_dirty_chunks", "gauge", Integer.toString(dirtyTracker.size()));
        appendMetric(out, "ef_mspt", "gauge",
                String.format(java.util.Locale.ROOT, "%.2f", currentMspt()));
        LivePlayerService players = livePlayers;
        if (players != null) {
            appendMetric(out, "ef_players_visible", "gauge",
                    Integer.toString(players.current().players().size()));
        }
        if (claimsLayer != null) {
            appendMetric(out, "ef_claims", "gauge", Integer.toString(claimsLayer.size()));
        }
        if (markersLayer != null) {
            appendMetric(out, "ef_markers", "gauge", Integer.toString(markersLayer.size()));
        }
        return out.toString();
    }

    private static void appendMetric(StringBuilder out, String name, String type, String value) {
        out.append("# TYPE ").append(name).append(' ').append(type).append("\n")
                .append(name).append(' ').append(value).append("\n");
    }

    private double currentMspt() {
        long[] tickTimes = server.getTickTimes();
        if (tickTimes == null || tickTimes.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long tick : tickTimes) {
            sum += tick;
        }
        return sum / (double) tickTimes.length / 1_000_000.0;
    }

    /** MSPT-based auto throttle: pauses backlog rendering while the server struggles. */
    private void autoThrottleTick() {
        if (!config.performance().autoThrottle() || scheduler == null) {
            return;
        }
        double mspt = currentMspt();
        if (!scheduler.isPaused() && mspt > config.performance().msptPauseThreshold()) {
            autoThrottled = true;
            scheduler.pause();
            LOGGER.warn("[ExplorersFriend/Renderer] Auto-throttle: rendering paused (avg {} ms/tick)",
                    String.format(java.util.Locale.ROOT, "%.1f", mspt));
        } else if (autoThrottled && scheduler.isPaused()
                && mspt < config.performance().msptResumeThreshold()) {
            autoThrottled = false;
            scheduler.resume();
            LOGGER.info("[ExplorersFriend/Renderer] Auto-throttle: rendering resumed (avg {} ms/tick)",
                    String.format(java.util.Locale.ROOT, "%.1f", mspt));
        }
    }

    /** Called by the pause/resume commands so manual control overrides the throttle. */
    public void clearAutoThrottle() {
        autoThrottled = false;
    }

    public MarkerStore markerStore() {
        return markerStore;
    }

    public BannerWatcher bannerWatcher() {
        return bannerWatcher;
    }

    public ClaimManager claimManager() {
        return claimManager;
    }

    private void schedulePeriodicTasks() {
        // Dirty-chunk debounce → snapshot requests.
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (!ready || stopping || readOnly) {
                    return;
                }
                MapConfig.Render render = config.render();
                for (DirtyTracker.DirtyChunk dirty : dirtyTracker.drainReady(
                        render.updateDebounceSeconds() * 1000L,
                        render.updateMaxDelaySeconds() * 1000L, 512)) {
                    DimensionInfo dim = dimensions.get(dirty.dimensionSlug());
                    ChunkSnapshotter snap = snapshotter;
                    if (dim != null && snap != null) {
                        snap.request(dim.world(), dirty.chunkX(), dirty.chunkZ());
                    }
                }
                fullRender.feed();
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Renderer] Scheduler tick failed: {}", e.toString());
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Persistence + watchdog.
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (!ready || stopping) {
                    return;
                }
                if (!readOnly) {
                    renderedIndex.saveIfDirty();
                    fullRender.persistAll();
                }
                scheduler.checkStuckWorkers(60_000);
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Cache] Periodic persistence failed: {}", e.toString());
            }
        }, 30, 30, TimeUnit.SECONDS);

        // Player sampling (server thread via execute) + skin head requests.
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (!ready || stopping || !config.players().show() || webServer == null) {
                    return;
                }
                server.execute(() -> {
                    LivePlayerService players = livePlayers;
                    if (players != null) {
                        players.sample(server, dimensions.keySet());
                    }
                    for (var player : server.getPlayerManager().getPlayerList()) {
                        skinService.requestHead(player);
                    }
                });
            } catch (Exception e) {
                LOGGER.debug("[ExplorersFriend/Web] Player sampling failed: {}", e.toString());
            }
        }, config.players().updateIntervalSeconds(), config.players().updateIntervalSeconds(), TimeUnit.SECONDS);

        // Auto-throttle + batched marker saves.
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (ready && !stopping) {
                    autoThrottleTick();
                }
            } catch (Exception e) {
                LOGGER.debug("[ExplorersFriend/Renderer] Throttle tick failed: {}", e.toString());
            }
        }, 10, 10, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(() -> {
            try {
                if (markerStore != null && !stopping) {
                    markerStore.saveIfDirty();
                }
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Markers] Periodic save failed: {}", e.toString());
            }
        }, config.markers().saveIntervalSeconds(), config.markers().saveIntervalSeconds(), TimeUnit.SECONDS);
    }

    // --- event handlers (registered in ExplorersFriend) ---------------------

    public void onEndTick() {
        if (!ready || stopping || readOnly) {
            return;
        }
        ChunkSnapshotter snap = snapshotter;
        if (snap != null) {
            snap.onEndTick(server);
        }
        BannerWatcher banners = bannerWatcher;
        if (banners != null) {
            banners.onEndTick();
        }
    }

    public void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (!ready || stopping || readOnly || !config.render().renderNewChunks()) {
            return;
        }
        String slug = slugOfWorld(world);
        if (slug == null) {
            return;
        }
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        if (!renderedIndex.isRendered(slug, chunkX, chunkZ)) {
            dirtyTracker.markDirty(slug, chunkX, chunkZ);
        }
        // Heal banner markers + register named banners, strictly chunk-local: inside a
        // CHUNK_LOAD callback any world.getBlockState/getBlockEntity would block on the
        // not-yet-registered chunk and deadlock the server thread (watchdog kill).
        BannerWatcher banners = bannerWatcher;
        OverlayLayer<MarkerStore.Item> markers = markersLayer;
        if (banners != null && markers != null) {
            List<BlockPos> knownBannerPositions = new java.util.ArrayList<>();
            for (MarkerStore.Item item : markers.queryBox(slug, chunkX << 4, chunkZ << 4,
                    (chunkX << 4) + 15, (chunkZ << 4) + 15, 16)) {
                if (item.marker().isBanner()) {
                    knownBannerPositions.add(new BlockPos(item.marker().x(), item.marker().y(),
                            item.marker().z()));
                }
            }
            banners.onChunkLoaded(world, chunk, knownBannerPositions);
        }
    }

    public void onChunkUnload(ServerWorld world, WorldChunk chunk) {
        ChunkSnapshotter snap = snapshotter;
        if (!ready || stopping || readOnly || snap == null) {
            return;
        }
        String slug = slugOfWorld(world);
        if (slug == null) {
            return;
        }
        if (dirtyTracker.clear(slug, chunk.getPos().x, chunk.getPos().z)) {
            snap.snapshotNow(world, chunk); // last chance to read it while loaded
        }
    }

    private void onBlockChanged(World world, BlockPos pos, net.minecraft.block.BlockState newState) {
        if (!ready || stopping || readOnly || !(world instanceof ServerWorld serverWorld)) {
            return;
        }
        String slug = slugOfWorld(serverWorld);
        if (slug != null) {
            dirtyTracker.markDirty(slug, pos.getX() >> 4, pos.getZ() >> 4);
            BannerWatcher banners = bannerWatcher;
            if (banners != null) {
                banners.onBlockChanged(serverWorld, pos, newState);
            }
        }
    }

    private String slugOfWorld(ServerWorld world) {
        String slug = TileStore.dimensionSlug(world.getRegistryKey().getValue().toString());
        return dimensions.containsKey(slug) ? slug : null;
    }

    // --- command support ----------------------------------------------------

    public boolean isReady() {
        return ready;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public MapConfig config() {
        return config;
    }

    public Map<String, DimensionInfo> dimensionInfos() {
        return dimensions;
    }

    public RenderScheduler scheduler() {
        return scheduler;
    }

    public FullRenderManager fullRenderManager() {
        return fullRender;
    }

    public DirtyTracker dirtyTracker() {
        return dirtyTracker;
    }

    public ColorPipeline.ColorData colorData() {
        return colorData;
    }

    public String webAddress() {
        return webServer == null ? null : webServer.bindDescription();
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Starts a full render; returns regions queued, -1 when already running, -2 when not ready. */
    public int startFullRender(String slug, int radiusBlocks) {
        if (!ready || readOnly) {
            return -2;
        }
        DimensionInfo dim = dimensions.get(slug);
        DimensionContext context = contexts.get(slug);
        if (dim == null || context == null) {
            return -3;
        }
        int radius = radiusBlocks > 0 ? radiusBlocks : config.worlds().maxRenderRadiusBlocks();
        return fullRender.start(context, dim.spawnX(), dim.spawnZ(), radius);
    }

    /**
     * Incremental update: schedules only regions whose file is newer than their tile
     * (or whose tile is missing). Runs the comparison asynchronously.
     *
     * @param feedback receives a human-readable result line (any thread)
     */
    public void startUpdateRender(String slug, Consumer<String> feedback) {
        if (!ready || readOnly) {
            feedback.accept("Service not ready or read-only.");
            return;
        }
        DimensionInfo dim = dimensions.get(slug);
        if (dim == null) {
            feedback.accept("Unknown or disabled dimension.");
            return;
        }
        scanPool.submit(() -> {
            int scheduled = 0;
            for (long[] region : FullRenderManager.enumerateRegions(dim.regionDir(),
                    dim.spawnX(), dim.spawnZ(), config.worlds().maxRenderRadiusBlocks())) {
                int regionX = (int) region[0];
                int regionZ = (int) region[1];
                try {
                    Path tile = tileStore.tilePath(slug, 0, regionX, regionZ);
                    Path regionFile = dim.regionDir().resolve("r." + regionX + "." + regionZ + ".mca");
                    boolean stale = !Files.exists(tile)
                            || Files.getLastModifiedTime(regionFile).compareTo(Files.getLastModifiedTime(tile)) > 0;
                    if (stale && scheduler.submitRegionRender(slug, regionX, regionZ,
                            RenderScheduler.PRIORITY_BACKLOG)) {
                        scheduled++;
                    }
                } catch (IOException ignored) {
                    // unreadable region: skip, full render can pick it up later
                }
            }
            LOGGER.info("[ExplorersFriend/Renderer] Update render for {}: {} stale region(s) scheduled",
                    dim.id(), scheduled);
            feedback.accept("Update: " + scheduled + " changed/missing region(s) scheduled for " + dim.id() + ".");
        });
    }

    /**
     * Stops the embedded web server and starts it again with a freshly loaded
     * configuration (new bind address/port/threads). Runs on the scan pool - closing
     * the listener can block up to ~1 s and must never stall the server thread.
     */
    public void restartWebServer(Consumer<String> feedback) {
        if (!ready || scanPool == null) {
            feedback.accept("Service not ready yet.");
            return;
        }
        scanPool.submit(() -> {
            synchronized (webRestartLock) {
                MapConfig newConfig = ConfigIO.loadOrCreate(configDir.resolve("config.jsonc"));
                this.config = newConfig;
                if (webServer != null) {
                    String old = webServer.bindDescription();
                    webServer.close();
                    webServer = null;
                    LOGGER.info("[ExplorersFriend/Web] Web server stopped for restart (was {})", old);
                }
                if (!newConfig.web().enabled()) {
                    feedback.accept("Web server stopped: web.enabled is false in the config.");
                    return;
                }
                startWebServer();
                feedback.accept(webServer != null
                        ? "Web server restarted: listening on " + webServer.bindDescription() + "."
                        : "Restart failed: could not bind " + newConfig.web().bind() + ":"
                        + newConfig.web().port() + " (see console; the previous server is stopped).");
            }
        });
        feedback.accept("Restarting the web server with the current config...");
    }

    private final Object webRestartLock = new Object();

    /** Reloads config values + manual colors; swaps palettes without a restart. */
    public String reload() {
        MapConfig newConfig = ConfigIO.loadOrCreate(configDir.resolve("config.jsonc"));
        this.config = newConfig;
        if (ready && colorData != null) {
            scanPool.submit(this::reloadColorsInternal);
            return "Configuration reloaded. Manual colors are being re-applied; "
                    + "for a new web bind/port run /efmap web restart, thread/storage changes need a server restart.";
        }
        return "Configuration reloaded (service not fully started yet).";
    }

    /** Reloads only block-colors.jsonc and swaps the palettes. */
    public String reloadColors() {
        if (!ready || colorData == null) {
            return "Service not ready yet.";
        }
        scanPool.submit(this::reloadColorsInternal);
        return "Manual block colors are being reloaded asynchronously.";
    }

    private void reloadColorsInternal() {
        try {
            ColorPipeline.ColorData data = colorData;
            var overrides = net.explorersfriend.color.ManualColorOverrides
                    .loadOrCreate(configDir.resolve("block-colors.jsonc"));
            var stateTable = net.explorersfriend.world.StateColorTable.build(data.results(), overrides,
                    Set.copyOf(config.blocks().excludeBlocks()), config.blocks().unknownBlockColor());
            var palette = new RuntimePalette(stateTable.nameView(), data.biomeTable().nameView(),
                    config.blocks().unknownBlockColor());
            colorData = new ColorPipeline.ColorData(data.results(), overrides, stateTable,
                    data.biomeTable(), palette, data.inventory(), data.scanStats(), data.fromCache(),
                    data.vanillaJar());
            rebuildContexts();
            LiveChunkExtractor liveExtractor = new LiveChunkExtractor(stateTable, data.biomeTable(),
                    config.render().waterDepthShading());
            snapshotter = new ChunkSnapshotter(liveExtractor, scheduler, this::slugOfWorld,
                    config.render().maxSnapshotsPerTick(), config.render().tickBudgetMicros());
            LOGGER.info("[ExplorersFriend/Colors] Palettes reloaded ({} manual override(s)); "
                    + "already rendered tiles update as chunks change or via /efmap render", overrides.size());
        } catch (Exception e) {
            LOGGER.error("[ExplorersFriend/Colors] Palette reload failed", e);
        }
    }

    /** Deletes tiles and caches. Destructive; guarded by the command layer. */
    public String pruneCaches(boolean alsoTiles) {
        if (readOnly) {
            return "Read-only mode: another instance owns the caches.";
        }
        int deleted = 0;
        deleted += net.explorersfriend.util.MoreFiles.deleteRecursivelyQuiet(cacheDir.resolve("render-progress"));
        for (String name : List.of("jar-inventory.json", "texture-colors.json",
                "block-colors.json", "rendered-chunks.json")) {
            try {
                if (Files.deleteIfExists(cacheDir.resolve(name))) {
                    deleted++;
                }
            } catch (IOException ignored) {
                // locked file: skipped
            }
        }
        if (alsoTiles && tileStore != null) {
            deleted += net.explorersfriend.util.MoreFiles.deleteRecursivelyQuiet(tileStore.root());
        }
        if (renderedIndex != null) {
            for (DimensionInfo dim : dimensions.values()) {
                renderedIndex.clearDimension(dim.slug());
            }
        }
        LOGGER.info("[ExplorersFriend/Cache] Pruned caches ({} file(s) removed, tiles included: {})",
                deleted, alsoTiles);
        return "Pruned " + deleted + " cache file(s)" + (alsoTiles ? " including all tiles" : "")
                + ". Caches rebuild on the next start / render.";
    }

    // --- shutdown -----------------------------------------------------------

    private void shutdown() {
        stopping = true;
        LOGGER.info("[ExplorersFriend/Init] Shutting down...");
        BlockChangeHooks.uninstall();
        if (sched != null) {
            sched.shutdownNow();
        }
        if (fullRender != null && !readOnly) {
            fullRender.persistAll();
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (webServer != null) {
            webServer.close();
            webServer = null;
        }
        if (scanPool != null) {
            scanPool.shutdownNow();
            try {
                if (!scanPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("[ExplorersFriend/Init] Scan pool did not stop within 5 s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (renderedIndex != null && !readOnly) {
            renderedIndex.saveIfDirty();
        }
        if (markerStore != null) {
            markerStore.saveIfDirty();
        }
        if (bannerIcons != null) {
            bannerIcons.close();
        }
        LivePlayerService players = livePlayers;
        if (players != null) {
            players.clear();
        }
        releaseLock();
        LOGGER.info("[ExplorersFriend/Init] Shutdown complete");
    }

    private void releaseLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            LOGGER.debug("[ExplorersFriend/Cache] Lock release failed: {}", e.toString());
        }
    }

    // --- web data source ----------------------------------------------------

    private void rebuildWorldsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("title", config.web().title());
        root.addProperty("playersIntervalSeconds", config.players().updateIntervalSeconds());
        JsonArray worlds = new JsonArray();
        for (DimensionInfo dim : dimensions.values()) {
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

    private byte[] buildStatusJson() {
        JsonObject root = new JsonObject();
        root.addProperty("name", ExplorersFriend.MOD_NAME);
        root.addProperty("ready", ready);
        root.addProperty("readOnly", readOnly);
        root.addProperty("uptimeSeconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
        RenderScheduler sched = scheduler;
        if (sched != null) {
            RenderScheduler.Stats stats = sched.stats();
            JsonObject render = new JsonObject();
            render.addProperty("queued", stats.queued());
            render.addProperty("paused", stats.paused());
            render.addProperty("tilesRendered", stats.tilesRendered());
            render.addProperty("tilesFailed", stats.tilesFailed());
            render.addProperty("chunksRendered", stats.chunksRendered());
            render.addProperty("avgTileMillis", Math.round(stats.avgTileMillis() * 10) / 10.0);
            root.add("render", render);
        }
        root.addProperty("dirtyChunks", dirtyTracker.size());
        ChunkSnapshotter snap = snapshotter;
        if (snap != null) {
            JsonObject snapshot = new JsonObject();
            for (Map.Entry<String, Long> entry : snap.statsSnapshot().entrySet()) {
                snapshot.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("snapshots", snapshot);
        }
        ColorPipeline.ColorData data = colorData;
        if (data != null) {
            JsonObject scan = new JsonObject();
            scan.addProperty("jars", data.inventory().totalJars());
            scan.addProperty("jarsUnchanged", data.inventory().unchanged());
            scan.addProperty("blockColors", data.results().size());
            scan.addProperty("fromCache", data.fromCache());
            if (data.scanStats() != null) {
                scan.addProperty("fallbacks", data.scanStats().fallbacks());
                scan.addProperty("errors", data.scanStats().errors());
            }
            root.add("scan", scan);
        }
        FullRenderManager renders = fullRender;
        if (renders != null) {
            JsonObject progress = new JsonObject();
            for (Map.Entry<String, int[]> entry : renders.progressSnapshot().entrySet()) {
                JsonArray pair = new JsonArray();
                pair.add(entry.getValue()[0]);
                pair.add(entry.getValue()[1]);
                progress.add(entry.getKey(), pair);
            }
            root.add("fullRender", progress);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private final class WebData implements MapHttpServer.DataSource {

        @Override
        public byte[] playersJson() {
            LivePlayerService players = livePlayers;
            return players == null
                    ? "{\"players\":[]}".getBytes(StandardCharsets.UTF_8)
                    : players.buildResponse(null, 0).toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] statusJson() {
            return buildStatusJson();
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
}
