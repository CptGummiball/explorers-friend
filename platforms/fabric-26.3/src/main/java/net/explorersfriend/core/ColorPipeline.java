package net.explorersfriend.core;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.api.ExplorersFriendApi;
import net.explorersfriend.color.BlockColorCache;
import net.explorersfriend.color.BlockColorResult;
import net.explorersfriend.color.ColorExtractor;
import net.explorersfriend.color.ColormapSampler;
import net.explorersfriend.color.ManualColorOverrides;
import net.explorersfriend.color.TextureColorCache;
import net.explorersfriend.color.TextureSampler;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.render.RuntimePalette;
import net.explorersfriend.resource.DirectoryResourceSource;
import net.explorersfriend.resource.ResourcePool;
import net.explorersfriend.resource.ResourceSource;
import net.explorersfriend.resource.VanillaAssetLocator;
import net.explorersfriend.resource.ZipResourceSource;
import net.explorersfriend.scan.InventoryCache;
import net.explorersfriend.scan.JarInventoryScanner;
import net.explorersfriend.scan.JarRecord;
import net.explorersfriend.world.BiomeTintTable;
import net.explorersfriend.world.StateColorTable;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The startup scan: JAR inventory → (when anything changed) resource scan and color
 * extraction → runtime tables. Runs entirely on the scan pool; registries are frozen
 * by SERVER_STARTED, so off-thread reads are safe.
 */
public final class ColorPipeline {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    /** Everything the rest of the mod needs after the pipeline ran. */
    public record ColorData(
            Map<String, BlockColorResult> results,
            Map<String, ManualColorOverrides.ColorOverride> overrides,
            StateColorTable stateTable,
            BiomeTintTable biomeTable,
            RuntimePalette palette,
            JarInventoryScanner.Result inventory,
            ColorExtractor.ScanStats scanStats,
            boolean fromCache,
            java.nio.file.Path vanillaJar) {
    }

    private ColorPipeline() {
    }

    public static ColorData run(RegistryAccess registryManager, String gameVersion,
                                MapConfig config, Path cacheDir, Path overridesFile,
                                ExecutorService scanPool) {
        long startNanos = System.nanoTime();
        LOGGER.info("[ExplorersFriend/Scanner] Starting mod JAR inventory...");
        Map<String, JarRecord> cachedInventory = InventoryCache.load(cacheDir.resolve("jar-inventory.json"));
        JarInventoryScanner.Result inventory = JarInventoryScanner.scan(cachedInventory, scanPool);
        try {
            InventoryCache.save(cacheDir.resolve("jar-inventory.json"), inventory.records());
        } catch (IOException e) {
            LOGGER.warn("[ExplorersFriend/Scanner] Could not save inventory cache: {}", e.toString());
        }
        LOGGER.info("[ExplorersFriend/Scanner] Mod inventory completed: {} mods, {} jars ({} ms)",
                FabricLoader.getInstance().getAllMods().size(), inventory.totalJars(),
                (System.nanoTime() - startNanos) / 1_000_000);
        LOGGER.info("[ExplorersFriend/Scanner] Cache hits: {} | New: {} | Changed: {} | Removed: {} | Duplicate contents: {}",
                inventory.unchanged(), inventory.added(), inventory.changed(),
                inventory.removed(), inventory.duplicateContents());

        String animationMode = config.scan().animatedTextures();
        Path blockColorFile = cacheDir.resolve("block-colors.json");

        // Vanilla assets are needed in every case (colormaps for biome tints).
        Path gameJar = gameJarCandidate();
        Optional<Path> vanillaJar = VanillaAssetLocator.locate(gameJar, cacheDir, gameVersion,
                config.scan().downloadVanillaAssets());

        Optional<Map<String, BlockColorResult>> cached = BlockColorCache.loadIfValid(
                blockColorFile, inventory.jarSetHash(), ColorExtractor.ALGORITHM_VERSION, animationMode);

        Map<String, BlockColorResult> results;
        ColorExtractor.ScanStats scanStats = null;
        ColormapSampler grassMap;
        ColormapSampler foliageMap;

        if (cached.isPresent()) {
            results = cached.get();
            LOGGER.info("[ExplorersFriend/Colors] Block colors loaded from cache: {} entries (mod set unchanged)",
                    results.size());
            try (ResourcePool colormapPool = buildPool(List.of(), inventory, config, vanillaJar)) {
                grassMap = loadColormap(colormapPool, "grass", RuntimePalette.DEFAULT_GRASS_RGB);
                foliageMap = loadColormap(colormapPool, "foliage", RuntimePalette.DEFAULT_FOLIAGE_RGB);
            }
        } else {
            LOGGER.info("[ExplorersFriend/Colors] Resource scan required (mods or algorithm changed)");
            TextureColorCache textureCache = TextureColorCache.load(cacheDir.resolve("texture-colors.json"),
                    ColorExtractor.ALGORITHM_VERSION, animationMode);
            List<String> blockIds = new ArrayList<>();
            for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                blockIds.add(id.toString());
            }
            blockIds.sort(Comparator.naturalOrder());

            try (ResourcePool pool = buildPool(modSources(inventory, config), inventory, config, vanillaJar)) {
                LOGGER.info("[ExplorersFriend/Colors] Scanning {} block(s) across {} resource source(s)...",
                        blockIds.size(), pool.sourceCount());
                TextureSampler sampler = new TextureSampler(config.scan().maxTextureEdge(), animationMode);
                ColorExtractor.Output output = ColorExtractor.extract(blockIds, pool, textureCache, sampler,
                        scanPool, config.scan(), config.blocks(), config.logging().progressIntervalSeconds());
                results = new HashMap<>(output.colors());
                scanStats = output.stats();
                grassMap = loadColormap(pool, "grass", RuntimePalette.DEFAULT_GRASS_RGB);
                foliageMap = loadColormap(pool, "foliage", RuntimePalette.DEFAULT_FOLIAGE_RGB);
            }
            applyApiProviders(results);
            try {
                textureCache.save(cacheDir.resolve("texture-colors.json"),
                        ColorExtractor.ALGORITHM_VERSION, animationMode);
                BlockColorCache.save(blockColorFile, inventory.jarSetHash(),
                        ColorExtractor.ALGORITHM_VERSION, animationMode, results);
            } catch (IOException e) {
                LOGGER.warn("[ExplorersFriend/Cache] Could not persist color caches: {}", e.toString());
            }
            ExplorersFriendApi.fireScanComplete(results.size(),
                    scanStats == null ? 0 : scanStats.fallbacks());
        }

        Map<String, ManualColorOverrides.ColorOverride> overrides = ManualColorOverrides.loadOrCreate(overridesFile);
        StateColorTable stateTable = StateColorTable.build(results, overrides,
                Set.copyOf(normalize(config.blocks().excludeBlocks())), config.blocks().unknownBlockColor());
        BiomeTintTable biomeTable = BiomeTintTable.build(
                registryManager.lookupOrThrow(Registries.BIOME), grassMap, foliageMap);
        RuntimePalette palette = new RuntimePalette(stateTable.nameView(), biomeTable.nameView(),
                config.blocks().unknownBlockColor());
        LOGGER.info("[ExplorersFriend/Colors] Runtime palette ready: {} block(s), {} biome(s), {} manual override(s)",
                palette.blockCount(), palette.biomeCount(), overrides.size());

        return new ColorData(results, overrides, stateTable, biomeTable, palette,
                inventory, scanStats, cached.isPresent(), vanillaJar.orElse(null));
    }

    private static void applyApiProviders(Map<String, BlockColorResult> results) {
        List<ExplorersFriendApi.BlockColorProvider> providers = ExplorersFriendApi.colorProviders();
        if (providers.isEmpty()) {
            return;
        }
        int applied = 0;
        for (Map.Entry<String, BlockColorResult> entry : new ArrayList<>(results.entrySet())) {
            for (ExplorersFriendApi.BlockColorProvider provider : providers) {
                try {
                    Integer color = provider.colorFor(entry.getKey());
                    if (color != null) {
                        BlockColorResult old = entry.getValue();
                        results.put(entry.getKey(), new BlockColorResult(old.blockId(), color, old.tint(),
                                "api-provider", old.modelId(), old.textureId(), null,
                                old.algorithmVersion(), System.currentTimeMillis()));
                        applied++;
                        break;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ExplorersFriend/Api] Color provider threw for {}: {}", entry.getKey(), e.toString());
                }
            }
        }
        if (applied > 0) {
            LOGGER.info("[ExplorersFriend/Colors] API color providers supplied {} color(s)", applied);
        }
    }

    /** Sources from mod containers via the loader's mounted root paths (handles nested JARs). */
    private static List<ResourceSource> modSources(JarInventoryScanner.Result inventory, MapConfig config) {
        Map<String, String> shaByModId = new HashMap<>();
        for (JarRecord record : inventory.records()) {
            shaByModId.putIfAbsent(record.modId(), record.sha256());
        }
        Set<String> excluded = Set.copyOf(config.scan().excludeMods());
        Set<String> seenRoots = new HashSet<>();
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator.comparing(mod -> mod.getMetadata().getId()));
        List<ResourceSource> sources = new ArrayList<>();
        for (ModContainer mod : mods) {
            String modId = mod.getMetadata().getId();
            if (modId.equals("minecraft") || modId.equals("java") || excluded.contains(modId)) {
                continue;
            }
            for (Path root : mod.getRootPaths()) {
                if (!seenRoots.add(root.toUri().toString())) {
                    continue;
                }
                String sha = shaByModId.getOrDefault(modId, "unknown");
                sources.add(new LoaderRootResourceSource(root, sha,
                        "mod '" + modId + "'", config.scan().maxEntryBytes()));
            }
        }
        return sources;
    }

    private static ResourcePool buildPool(List<ResourceSource> modSources,
                                          JarInventoryScanner.Result inventory,
                                          MapConfig config, Optional<Path> vanillaJar) {
        List<ResourceSource> sources = new ArrayList<>(modSources);
        if (vanillaJar.isPresent()) {
            try {
                sources.add(new ZipResourceSource(vanillaJar.get(), "vanilla",
                        "vanilla client assets", config.scan().maxZipEntries(), config.scan().maxEntryBytes()));
            } catch (IOException e) {
                LOGGER.warn("[ExplorersFriend/Scanner] Could not open vanilla assets: {}", e.toString());
            }
        }
        return new ResourcePool(sources);
    }

    private static ColormapSampler loadColormap(ResourcePool pool, String name, int fallbackRgb) {
        byte[] png = pool.read("assets/minecraft/textures/colormap/" + name + ".png");
        if (png == null) {
            LOGGER.warn("[ExplorersFriend/Colors] Colormap '{}' unavailable; using a constant fallback tint", name);
            return ColormapSampler.constant(fallbackRgb);
        }
        try {
            return ColormapSampler.fromPng(png, fallbackRgb);
        } catch (IOException e) {
            LOGGER.warn("[ExplorersFriend/Colors] Colormap '{}' unreadable ({}); using fallback", name, e.toString());
            return ColormapSampler.constant(fallbackRgb);
        }
    }

    private static Path gameJarCandidate() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getOrigin().getPaths())
                .filter(paths -> !paths.isEmpty())
                .map(paths -> paths.get(0))
                .filter(Files::isRegularFile)
                .orElse(null);
    }

    private static List<String> normalize(List<String> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(net.explorersfriend.resource.ResourcePaths.normalizeId(id.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** NIO-path-based source over a Fabric loader mod root (works for nested JARs and dev dirs). */
    static final class LoaderRootResourceSource implements ResourceSource {

        private final Path root;
        private final String sourceId;
        private final String description;
        private final long maxEntryBytes;

        LoaderRootResourceSource(Path root, String sourceId, String description, long maxEntryBytes) {
            this.root = root;
            this.sourceId = sourceId;
            this.description = description;
            this.maxEntryBytes = maxEntryBytes;
        }

        @Override
        public byte[] read(String path) throws IOException {
            if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
                return null;
            }
            Path resolved = root.resolve(path);
            if (!Files.isRegularFile(resolved)) {
                return null;
            }
            long size = Files.size(resolved);
            if (size > maxEntryBytes) {
                throw new IOException(path + " is " + size + " bytes (limit " + maxEntryBytes + ")");
            }
            return Files.readAllBytes(resolved);
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String describe() {
            return description;
        }

        @Override
        public void close() {
            // roots are owned by Fabric Loader; nothing to close here
        }
    }
}
