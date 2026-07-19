package net.explorersfriend.color;

import net.explorersfriend.util.Log;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.resource.ResourcePaths;
import net.explorersfriend.resource.ResourcePool;
import net.explorersfriend.util.Hashes;
import net.explorersfriend.util.RateLimitedLog;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the full block → color resolution for a list of block ids.
 *
 * <p>Runs on the scan pool; each block is independent, failures are isolated per block
 * and produce a fallback color plus an aggregated WARN instead of aborting the scan.
 * Bump {@link #ALGORITHM_VERSION} whenever the sampling/resolution logic changes —
 * every color cache keys on it and recomputes automatically.</p>
 */
public final class ColorExtractor {

    public static final int ALGORITHM_VERSION = 1;

    private static final Logger LOGGER = Log.LOGGER;

    /** Blocks the biome colormaps tint even though vanilla models carry no tintindex hint we parse. */
    private static final Set<String> GRASS_TINTED = Set.of(
            "minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass",
            "minecraft:fern", "minecraft:large_fern", "minecraft:sugar_cane");

    public record ScanStats(
            int blocksTotal,
            int resolved,
            int fallbacks,
            int errors,
            int modelsParsed,
            long textureCacheHits,
            long textureCacheMisses,
            double textureCacheHitPercent) {
    }

    public record Output(Map<String, BlockColorResult> colors, ScanStats stats) {
    }

    private ColorExtractor() {
    }

    public static Output extract(List<String> blockIds,
                                 ResourcePool pool,
                                 TextureColorCache textureCache,
                                 TextureSampler sampler,
                                 ExecutorService workers,
                                 MapConfig.Scan scanConfig,
                                 MapConfig.Blocks blocksConfig,
                                 int progressIntervalSeconds) {
        ModelResolver resolver = new ModelResolver(pool);
        ConcurrentHashMap<String, BlockColorResult> results = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        RateLimitedLog progressLog = new RateLimitedLog();
        Set<String> excludedNamespaces = Set.copyOf(scanConfig.excludeNamespaces());
        long startNanos = System.nanoTime();
        int total = blockIds.size();

        List<Future<?>> futures = new ArrayList<>();
        for (String blockId : blockIds) {
            futures.add(workers.submit(() -> {
                BlockColorResult result;
                try {
                    result = resolveOne(blockId, resolver, pool, textureCache, sampler,
                            excludedNamespaces, blocksConfig.unknownBlockColor());
                } catch (Exception e) {
                    errors.incrementAndGet();
                    result = fallbackResult(blockId, blocksConfig.unknownBlockColor(),
                            "error: " + e.getMessage());
                }
                if (result.isFallback()) {
                    fallbacks.incrementAndGet();
                }
                results.put(blockId, result);
                int count = done.incrementAndGet();
                if (progressLog.shouldLog("scan-progress", progressIntervalSeconds * 1000L)) {
                    LOGGER.info("[ExplorersFriend/Colors] Resolving block colors: {}/{} ({}%)",
                            count, total, count * 100 / Math.max(1, total));
                }
            }));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Colors] Worker task failed: {}", e.toString());
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        ScanStats stats = new ScanStats(total, total - fallbacks.get(), fallbacks.get(), errors.get(),
                resolver.parsedModelCount(), textureCache.hitCount(), textureCache.missCount(),
                textureCache.hitRatioPercent());
        LOGGER.info("[ExplorersFriend/Colors] Resolved {} blocks in {} ms "
                        + "({} models parsed, texture cache hits: {}%, fallbacks: {}, errors: {})",
                total, elapsedMs, stats.modelsParsed(),
                String.format(java.util.Locale.ROOT, "%.1f", stats.textureCacheHitPercent()),
                stats.fallbacks(), stats.errors());
        return new Output(Map.copyOf(results), stats);
    }

    private static BlockColorResult resolveOne(String blockId,
                                               ModelResolver resolver,
                                               ResourcePool pool,
                                               TextureColorCache textureCache,
                                               TextureSampler sampler,
                                               Set<String> excludedNamespaces,
                                               int unknownColor) {
        String namespace = ResourcePaths.namespaceOf(blockId);
        if (excludedNamespaces.contains(namespace)) {
            return fallbackResult(blockId, unknownColor, "namespace excluded by config");
        }

        ModelResolver.Resolution resolution = resolver.resolveBlock(blockId);
        if (resolution == null) {
            return builtinOrUnknown(blockId, unknownColor, "no resolvable model");
        }

        ResourcePool.Found texture = pool.find(ResourcePaths.texturePath(resolution.textureId()));
        if (texture == null) {
            return builtinOrUnknown(blockId, unknownColor,
                    "texture " + resolution.textureId() + " missing");
        }

        String textureSha = Hashes.sha256Hex(texture.data());
        Integer color = textureCache.get(textureSha);
        if (color == null) {
            byte[] mcmeta = pool.read(ResourcePaths.textureMetaPath(resolution.textureId()));
            try {
                color = sampler.sample(texture.data(), mcmeta);
            } catch (IOException e) {
                return builtinOrUnknown(blockId, unknownColor,
                        "texture " + resolution.textureId() + " unreadable: " + e.getMessage());
            }
            textureCache.put(textureSha, color);
        }

        TintType tint = classifyTint(blockId, resolution.tinted());
        return new BlockColorResult(blockId, color, tint, texture.source().sourceId(),
                resolution.modelId(), resolution.textureId(), null,
                ALGORITHM_VERSION, System.currentTimeMillis());
    }

    private static BlockColorResult builtinOrUnknown(String blockId, int unknownColor, String reason) {
        Integer builtin = FallbackPalette.get(blockId);
        if (builtin != null) {
            return new BlockColorResult(blockId, builtin, classifyTint(blockId, false), "builtin",
                    null, null, reason + " (builtin palette used)", ALGORITHM_VERSION,
                    System.currentTimeMillis());
        }
        return fallbackResult(blockId, unknownColor, reason);
    }

    private static BlockColorResult fallbackResult(String blockId, int unknownColor, String reason) {
        return new BlockColorResult(blockId, unknownColor, TintType.NONE, "fallback",
                null, null, reason, ALGORITHM_VERSION, System.currentTimeMillis());
    }

    /**
     * Deterministic tint classification: explicit water blocks, known vanilla grass-family
     * ids, leaf-like ids, otherwise the model's tintindex flag decides (foliage when the
     * id looks leafy, grass tint as the default guess for tinted modded blocks).
     */
    static TintType classifyTint(String blockId, boolean modelTinted) {
        if (blockId.equals("minecraft:water") || blockId.equals("minecraft:bubble_column")) {
            return TintType.WATER;
        }
        if (GRASS_TINTED.contains(blockId)) {
            return TintType.GRASS;
        }
        boolean leafy = blockId.endsWith("_leaves") || blockId.contains("leaves")
                || blockId.endsWith(":vine") || blockId.endsWith("_vine") || blockId.endsWith("_vines");
        if (leafy) {
            return TintType.FOLIAGE;
        }
        if (modelTinted) {
            return TintType.GRASS;
        }
        return TintType.NONE;
    }
}
