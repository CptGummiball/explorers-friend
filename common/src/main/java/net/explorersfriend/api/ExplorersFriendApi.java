package net.explorersfriend.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal public API (stability: experimental in 0.1.x — signatures may change in minor
 * versions; the class and concept will stay).
 *
 * <p>Register everything during your own mod's initialization, before the server
 * starts. All callbacks are invoked on background worker threads — never block them
 * for long and do not touch live world state from them.</p>
 */
public final class ExplorersFriendApi {

    /**
     * Supplies colors for blocks (e.g. blocks with dynamic textures the scanner cannot
     * resolve). Consulted after automatic texture resolution but below manual user
     * overrides. Return {@code null} to leave the block untouched.
     */
    @FunctionalInterface
    public interface BlockColorProvider {
        /** @return ARGB color for the block id ("mymod:my_block"), or null. */
        Integer colorFor(String blockId);
    }

    /** Notified once the startup resource scan and color resolution finished. */
    @FunctionalInterface
    public interface ScanCompleteListener {
        void onScanComplete(int totalBlocks, int fallbackBlocks);
    }

    /** Notified after a tile has been written (or deleted because it became empty). */
    @FunctionalInterface
    public interface TileRenderedListener {
        void onTileRendered(String dimensionSlug, int zoom, int tileX, int tileZ);
    }

    private static final CopyOnWriteArrayList<BlockColorProvider> COLOR_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ScanCompleteListener> SCAN_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TileRenderedListener> TILE_LISTENERS = new CopyOnWriteArrayList<>();

    private ExplorersFriendApi() {
    }

    public static void registerBlockColorProvider(BlockColorProvider provider) {
        COLOR_PROVIDERS.add(provider);
    }

    public static void registerScanCompleteListener(ScanCompleteListener listener) {
        SCAN_LISTENERS.add(listener);
    }

    public static void registerTileRenderedListener(TileRenderedListener listener) {
        TILE_LISTENERS.add(listener);
    }

    // --- internal bridge (not API) ----------------------------------------

    public static List<BlockColorProvider> colorProviders() {
        return COLOR_PROVIDERS;
    }

    public static void fireScanComplete(int totalBlocks, int fallbackBlocks) {
        for (ScanCompleteListener listener : SCAN_LISTENERS) {
            try {
                listener.onScanComplete(totalBlocks, fallbackBlocks);
            } catch (Exception e) {
                net.explorersfriend.util.Log.LOGGER
                        .warn("[ExplorersFriend/Api] Scan listener threw: {}", e.toString());
            }
        }
    }

    public static void fireTileRendered(String dimensionSlug, int zoom, int tileX, int tileZ) {
        for (TileRenderedListener listener : TILE_LISTENERS) {
            try {
                listener.onTileRendered(dimensionSlug, zoom, tileX, tileZ);
            } catch (Exception e) {
                net.explorersfriend.util.Log.LOGGER
                        .warn("[ExplorersFriend/Api] Tile listener threw: {}", e.toString());
            }
        }
    }
}
