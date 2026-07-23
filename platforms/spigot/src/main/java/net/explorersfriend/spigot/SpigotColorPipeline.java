package net.explorersfriend.spigot;

import net.explorersfriend.color.BiomeJsonTints;
import net.explorersfriend.color.BlockColorCache;
import net.explorersfriend.color.BlockColorResult;
import net.explorersfriend.color.BlockInfoMaps;
import net.explorersfriend.color.ColorExtractor;
import net.explorersfriend.color.ColormapSampler;
import net.explorersfriend.color.ManualColorOverrides;
import net.explorersfriend.color.TextureColorCache;
import net.explorersfriend.color.TextureSampler;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.render.RuntimePalette;
import net.explorersfriend.resource.ResourcePool;
import net.explorersfriend.resource.ResourceSource;
import net.explorersfriend.resource.VanillaAssetLocator;
import net.explorersfriend.resource.ZipResourceSource;
import net.explorersfriend.scan.InventoryCache;
import net.explorersfriend.scan.JarInventoryScanner;
import net.explorersfriend.scan.JarRecord;
import net.explorersfriend.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Block-color pipeline for the Spigot/Paper backend. Same shared machinery as the
 * mod loaders (inventory cache, texture/color caches keyed by jar-set hash,
 * blockstate-model-texture resolver, colormaps), fed from the sources that exist
 * on a Bukkit server: the server jar, the plugins directory, and the one-time
 * SHA-verified vanilla client-jar download for textures. Block ids come from the
 * Bukkit Material registry (passed in by the plugin); biome tints from the
 * worldgen JSONs inside the server jar. There is deliberately no "mod jar scan"
 * here - Bukkit servers do not load loader mods (documented in MULTIPLATFORM.md).
 */
final class SpigotColorPipeline {

    record ColorData(
            Map<String, BlockColorResult> results,
            RuntimePalette palette,
            JarInventoryScanner.Result inventory,
            boolean fromCache) {
    }

    private SpigotColorPipeline() {
    }

    static ColorData run(Path serverJar, Path pluginsDir, List<String> blockIds,
                         String mcVersion, MapConfig config, Path cacheDir,
                         Path overridesFile, ExecutorService scanPool) {
        long startNanos = System.nanoTime();
        List<JarInventoryScanner.PlainJar> jars = new ArrayList<>();
        if (serverJar != null) {
            jars.add(new JarInventoryScanner.PlainJar("server", mcVersion, serverJar));
        }
        if (pluginsDir != null && Files.isDirectory(pluginsDir)) {
            try (Stream<Path> stream = Files.list(pluginsDir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .forEach(p -> jars.add(new JarInventoryScanner.PlainJar(
                                p.getFileName().toString(), "unknown", p)));
            } catch (IOException e) {
                Log.LOGGER.warn("[ExplorersFriend/Scanner] Cannot list plugins dir: {}", e.toString());
            }
        }
        Map<String, JarRecord> cachedInventory = InventoryCache.load(cacheDir.resolve("jar-inventory.json"));
        JarInventoryScanner.Result inventory = JarInventoryScanner.scanJars(jars, cachedInventory, scanPool);
        try {
            InventoryCache.save(cacheDir.resolve("jar-inventory.json"), inventory.records());
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Scanner] Could not save inventory cache: {}", e.toString());
        }
        Log.LOGGER.info("[ExplorersFriend/Scanner] Server inventory completed: {} jar(s) ({} ms)",
                inventory.totalJars(), (System.nanoTime() - startNanos) / 1_000_000);
        Log.LOGGER.info("[ExplorersFriend/Scanner] Cache hits: {} | New: {} | Changed: {} | Removed: {} | Duplicate contents: {}",
                inventory.unchanged(), inventory.added(), inventory.changed(),
                inventory.removed(), inventory.duplicateContents());

        String animationMode = config.scan().animatedTextures();
        Path blockColorFile = cacheDir.resolve("block-colors.json");
        Optional<Path> vanillaJar = VanillaAssetLocator.locate(serverJar, cacheDir, mcVersion,
                config.scan().downloadVanillaAssets());

        Optional<Map<String, BlockColorResult>> cached = BlockColorCache.loadIfValid(
                blockColorFile, inventory.jarSetHash(), ColorExtractor.ALGORITHM_VERSION, animationMode);

        Map<String, BlockColorResult> results;
        ColormapSampler grassMap;
        ColormapSampler foliageMap;
        if (cached.isPresent()) {
            results = cached.get();
            Log.LOGGER.info("[ExplorersFriend/Colors] Block colors loaded from cache: {} entries "
                    + "(jar set unchanged)", results.size());
            try (ResourcePool pool = buildPool(List.of(), config, vanillaJar)) {
                grassMap = loadColormap(pool, "grass", RuntimePalette.DEFAULT_GRASS_RGB);
                foliageMap = loadColormap(pool, "foliage", RuntimePalette.DEFAULT_FOLIAGE_RGB);
            }
        } else {
            Log.LOGGER.info("[ExplorersFriend/Colors] Resource scan required (server/plugin jars or algorithm changed)");
            TextureColorCache textureCache = TextureColorCache.load(cacheDir.resolve("texture-colors.json"),
                    ColorExtractor.ALGORITHM_VERSION, animationMode);
            List<ResourceSource> extraSources = new ArrayList<>();
            if (serverJar != null) {
                extraSources.add(openZip(serverJar, "server", "server jar", config));
            }
            for (JarInventoryScanner.PlainJar jar : jars) {
                if (!"server".equals(jar.ownerId())) {
                    extraSources.add(openZip(jar.jar(), jar.ownerId(), "plugin '" + jar.ownerId() + "'", config));
                }
            }
            extraSources.removeIf(java.util.Objects::isNull);
            try (ResourcePool pool = buildPool(extraSources, config, vanillaJar)) {
                Log.LOGGER.info("[ExplorersFriend/Colors] Scanning {} block(s) across {} resource source(s)...",
                        blockIds.size(), pool.sourceCount());
                TextureSampler sampler = new TextureSampler(config.scan().maxTextureEdge(), animationMode);
                ColorExtractor.Output output = ColorExtractor.extract(blockIds, pool, textureCache, sampler,
                        scanPool, config.scan(), config.blocks(), config.logging().progressIntervalSeconds());
                results = new HashMap<>(output.colors());
                grassMap = loadColormap(pool, "grass", RuntimePalette.DEFAULT_GRASS_RGB);
                foliageMap = loadColormap(pool, "foliage", RuntimePalette.DEFAULT_FOLIAGE_RGB);
            }
            try {
                textureCache.save(cacheDir.resolve("texture-colors.json"),
                        ColorExtractor.ALGORITHM_VERSION, animationMode);
                BlockColorCache.save(blockColorFile, inventory.jarSetHash(),
                        ColorExtractor.ALGORITHM_VERSION, animationMode, results);
            } catch (IOException e) {
                Log.LOGGER.warn("[ExplorersFriend/Cache] Could not persist color caches: {}", e.toString());
            }
        }

        Map<String, ManualColorOverrides.ColorOverride> overrides = ManualColorOverrides.loadOrCreate(overridesFile);
        Map<String, int[]> biomeTints = BiomeJsonTints.fromJar(serverJar, grassMap, foliageMap);
        if (biomeTints.isEmpty()) {
            Log.LOGGER.warn("[ExplorersFriend/Colors] No worldgen biome data found in the server jar - "
                    + "using default tints (documented limitation)");
        }
        Set<String> excluded = new java.util.HashSet<>();
        for (String block : config.blocks().excludeBlocks()) {
            String id = block.toLowerCase(Locale.ROOT);
            excluded.add(id.contains(":") ? id : "minecraft:" + id);
        }
        RuntimePalette palette = new RuntimePalette(
                BlockInfoMaps.build(blockIds, results, overrides, excluded, config.blocks().unknownBlockColor()),
                biomeTints, config.blocks().unknownBlockColor());
        Log.LOGGER.info("[ExplorersFriend/Colors] Runtime palette ready: {} block(s), {} biome(s), {} manual override(s)",
                palette.blockCount(), palette.biomeCount(), overrides.size());
        return new ColorData(results, palette, inventory, cached.isPresent());
    }

    private static ResourceSource openZip(Path jar, String id, String describe, MapConfig config) {
        try {
            return new ZipResourceSource(jar, id, describe,
                    config.scan().maxZipEntries(), config.scan().maxEntryBytes());
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Scanner] Could not open {}: {}", describe, e.toString());
            return null;
        }
    }

    private static ResourcePool buildPool(List<ResourceSource> extraSources,
                                          MapConfig config, Optional<Path> vanillaJar) {
        List<ResourceSource> sources = new ArrayList<>(extraSources);
        vanillaJar.ifPresent(jar -> {
            ResourceSource vanilla = openZip(jar, "vanilla", "vanilla client assets", config);
            if (vanilla != null) {
                sources.add(vanilla);
            }
        });
        sources.sort(Comparator.comparing(ResourceSource::sourceId));
        return new ResourcePool(sources);
    }

    private static ColormapSampler loadColormap(ResourcePool pool, String name, int fallbackRgb) {
        byte[] png = pool.read("assets/minecraft/textures/colormap/" + name + ".png");
        if (png == null) {
            Log.LOGGER.warn("[ExplorersFriend/Colors] Colormap '{}' unavailable; using a constant fallback tint", name);
            return ColormapSampler.constant(fallbackRgb);
        }
        try {
            return ColormapSampler.fromPng(png, fallbackRgb);
        } catch (IOException e) {
            Log.LOGGER.warn("[ExplorersFriend/Colors] Colormap '{}' unreadable ({}); using fallback", name, e.toString());
            return ColormapSampler.constant(fallbackRgb);
        }
    }
}
