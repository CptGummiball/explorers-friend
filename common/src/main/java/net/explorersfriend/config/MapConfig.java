package net.explorersfriend.config;

import java.util.List;

/**
 * Immutable, validated runtime configuration. Produced by {@link ConfigIO}; every field
 * is guaranteed to be inside its documented safe range — invalid user input is replaced
 * by the default with a WARN, never by a crash.
 */
public record MapConfig(
        Web web,
        Render render,
        Scan scan,
        Storage storage,
        Worlds worlds,
        Players players,
        Logging logging,
        Blocks blocks,
        Claims claims,
        Markers markers,
        Waystones waystones,
        Performance performance) {

    public record Web(
            boolean enabled,
            String bind,
            int port,
            String publicBaseUrl,
            String title,
            int threads,
            boolean gzip,
            int connectionLimit,
            int idleTimeoutSeconds,
            boolean metricsEnabled) {
    }

    public record Render(
            int workers,
            int tickBudgetMicros,
            int maxSnapshotsPerTick,
            int maxQueuedTiles,
            int zoomLevels,
            boolean heightShading,
            boolean waterDepthShading,
            int updateDebounceSeconds,
            int updateMaxDelaySeconds,
            boolean fullRenderOnFirstStart,
            boolean renderNewChunks) {
    }

    public record Scan(
            int threads,
            boolean downloadVanillaAssets,
            /** "first_frame" or "average" */
            String animatedTextures,
            List<String> excludeMods,
            List<String> excludeNamespaces,
            int maxTextureEdge,
            int maxZipEntries,
            long maxEntryBytes) {
    }

    public record Storage(
            String dataDir,
            long maxTileCacheMb,
            boolean pruneCachesOnStart) {
    }

    public record Worlds(
            List<String> enabled,
            List<String> disabled,
            int maxRenderRadiusBlocks) {
    }

    public record Players(
            boolean show,
            boolean defaultVisibleInUi,
            int updateIntervalSeconds,
            boolean hideInvisible,
            boolean hideSpectators,
            int positionRounding,
            int positionDelaySeconds,
            boolean showNames,
            boolean showCoordinates,
            boolean anonymizeNames,
            boolean allowExternalSkinLookup,
            int skinCacheHours,
            List<String> hiddenPlayers,
            List<String> disabledWorlds) {
    }

    public record Logging(
            int progressIntervalSeconds,
            boolean debug) {
    }

    public record Blocks(
            int unknownBlockColor,
            List<String> excludeBlocks) {
    }

    public record Claims(
            boolean enabled,
            boolean defaultVisibleInUi,
            int refreshIntervalSeconds,
            double fillOpacity,
            double borderOpacity,
            int borderWidth,
            boolean showOwner,
            boolean showName,
            boolean showTeam,
            List<String> enabledProviders,
            List<String> disabledWorlds,
            int maxClaimsPerResponse,
            int defaultColor) {
    }

    public record Markers(
            boolean enabled,
            boolean defaultVisibleInUi,
            boolean bannersDefaultVisibleInUi,
            int maxPerPlayer,
            int maxTotal,
            boolean allowPlayerCreation,
            boolean allowBannerMarkers,
            boolean removeMarkerWithBanner,
            boolean showCreator,
            boolean showCoordinates,
            List<String> disabledWorlds,
            int saveIntervalSeconds,
            CustomIcons customIcons) {
    }

    /** User-supplied marker icons (config/explorersfriend/icons/, PNG/JPEG only). */
    public record CustomIcons(
            boolean enabled,
            int maxCount,
            int maxEdgePx,
            int maxFileKiB) {
    }

    /** Waystones-mod overlay layer (only used when the Waystones mod is installed). */
    public record Waystones(
            boolean enabled,
            boolean onlyGlobal,
            boolean showOwner,
            int refreshSeconds,
            List<String> disabledWorlds,
            boolean defaultVisibleInUi) {
    }

    public record Performance(
            boolean autoThrottle,
            int msptPauseThreshold,
            int msptResumeThreshold) {
    }

    public static MapConfig defaults() {
        return new MapConfig(
                new Web(true, "127.0.0.1", 8080, "", "The Explorer's Friend", 2, true, 64, 30, true),
                new Render(2, 1500, 4, 4096, 4, true, true, 5, 60, false, true),
                new Scan(2, true, "first_frame", List.of(), List.of(), 4096, 100_000, 32L * 1024 * 1024),
                new Storage("explorersfriend", 0, false),
                new Worlds(List.of("*"), List.of(), 0),
                new Players(true, true, 2, true, true, 1, 0, true, true, false, true, 24,
                        List.of(), List.of()),
                new Logging(5, false),
                new Blocks(0xFF7F7F7F, List.of()),
                new Claims(true, true, 60, 0.30, 1.0, 2, true, true, true,
                        List.of("*"), List.of(), 5000, 0x4080FF),
                new Markers(true, true, true, 30, 5000, true, true, true, false, true,
                        List.of(), 30,
                        new CustomIcons(true, 64, 128, 256)),
                new Waystones(true, false, false, 60, List.of(), true),
                new Performance(true, 45, 35));
    }
}
