package net.explorersfriend.marker;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.config.MapConfig;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns anvil-renamed banners into markers. Fed by the existing block-change mixin
 * funnel (placement, destruction, explosions, pistons, mods — everything goes through
 * {@code setBlockState}); the actual block-entity read happens one tick later in a
 * bounded end-of-tick queue, because the custom name is written to the block entity
 * after {@code setBlockState} returns.
 *
 * <p>Un-named banners never create markers. Two equally named banners at different
 * positions stay separate (position-keyed ids). Chunk re-loads cause no duplicates
 * (idempotent upserts); on chunk load, banner markers inside that chunk are verified
 * against the world so stale markers heal themselves.</p>
 */
public final class BannerWatcher {

    private static final int MAX_CHECKS_PER_TICK = 64;
    private static final int MAX_QUEUE = 4096;

    private final MarkerStore store;
    private final MapConfig.Markers config;
    private final Function<ServerWorld, String> slugOf;

    private final ArrayDeque<long[]> queue = new ArrayDeque<>(); // {posLong, worldIdentityHash} guarded by this
    private final Set<Long> queued = new HashSet<>();
    private final java.util.Map<Long, ServerWorld> queuedWorlds = new java.util.HashMap<>();

    public BannerWatcher(MarkerStore store, MapConfig.Markers config, Function<ServerWorld, String> slugOf) {
        this.store = store;
        this.config = config;
        this.slugOf = slugOf;
    }

    /** Called from the block-change hook (server thread, hot path — keep O(1)). */
    public void onBlockChanged(ServerWorld world, BlockPos pos, BlockState newState) {
        if (!config.allowBannerMarkers()) {
            return;
        }
        boolean bannerNow = newState.getBlock() instanceof AbstractBannerBlock;
        String slug = slugOf.apply(world);
        if (slug == null) {
            return;
        }
        if (bannerNow) {
            enqueue(world, pos);
        } else {
            // a marker at this exact position means a banner was here before
            String id = MapMarker.bannerId(slug, pos.getX(), pos.getY(), pos.getZ());
            if (store.byId(id).isPresent() && config.removeMarkerWithBanner()) {
                if (store.remove(id)) {
                    ExplorersFriend.LOGGER.info("[ExplorersFriend/Banners] Banner marker removed at {} {},{},{}",
                            slug, pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
    }

    private synchronized void enqueue(ServerWorld world, BlockPos pos) {
        if (queue.size() >= MAX_QUEUE) {
            return;
        }
        long key = pos.asLong() ^ ((long) System.identityHashCode(world) << 17);
        if (queued.add(key)) {
            queue.addLast(new long[]{pos.asLong(), key});
            queuedWorlds.put(key, world);
        }
    }

    /** End-of-tick: reads queued banner block entities (server thread, bounded). */
    public void onEndTick() {
        for (int i = 0; i < MAX_CHECKS_PER_TICK; i++) {
            long[] entry;
            ServerWorld world;
            synchronized (this) {
                entry = queue.pollFirst();
                if (entry == null) {
                    return;
                }
                queued.remove(entry[1]);
                world = queuedWorlds.remove(entry[1]);
            }
            if (world != null) {
                checkBanner(world, BlockPos.fromLong(entry[0]));
            }
        }
    }

    /**
     * Verifies one position (server thread) without ever blocking: the chunk is
     * resolved with {@code create=false}; if it is not fully loaded the check is
     * skipped — never treated as "banner gone" — and heals on the next chunk load.
     * Blocking lookups here previously self-deadlocked the server when this ran
     * inside a chunk callback (the world path lands in {@code getChunkBlocking}).
     */
    public void checkBanner(ServerWorld world, BlockPos pos) {
        net.minecraft.world.chunk.Chunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.chunk.ChunkStatus.FULL, false);
        if (chunk instanceof net.minecraft.world.chunk.WorldChunk worldChunk) {
            checkBannerInChunk(world, worldChunk, pos);
        }
    }

    /**
     * CHUNK_LOAD entry point: operates exclusively on the chunk instance the event
     * delivered — no {@code world.getBlockState}/{@code getBlockEntity}, which would
     * block on the not-yet-registered chunk (watchdog deadlock). Heals known banner
     * markers and additionally registers named banners found among the chunk's own
     * block entities (covers dispenser-/mod-placed or pre-existing banners for free).
     */
    public void onChunkLoaded(ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk,
                              java.util.List<BlockPos> knownMarkerPositions) {
        if (!config.allowBannerMarkers()) {
            return;
        }
        for (BlockPos pos : knownMarkerPositions) {
            checkBannerInChunk(world, chunk, pos);
        }
        for (net.minecraft.block.entity.BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof BannerBlockEntity banner && banner.getCustomName() != null) {
                checkBannerInChunk(world, chunk, blockEntity.getPos());
            }
        }
    }

    /** Chunk-local verification: named banner → upsert, otherwise remove. Never touches the world lookup path. */
    void checkBannerInChunk(ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk, BlockPos pos) {
        String slug = slugOf.apply(world);
        if (slug == null) {
            return;
        }
        if (pos.getX() >> 4 != chunk.getPos().x || pos.getZ() >> 4 != chunk.getPos().z) {
            return; // outside this chunk: defer to that chunk's own load event
        }
        String id = MapMarker.bannerId(slug, pos.getX(), pos.getY(), pos.getZ());
        BlockState state = chunk.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractBannerBlock bannerBlock)
                || !(chunk.getBlockEntity(pos) instanceof BannerBlockEntity banner)) {
            if (config.removeMarkerWithBanner() && store.remove(id)) {
                ExplorersFriend.LOGGER.info(
                        "[ExplorersFriend/Banners] Stale banner marker removed at {} {},{},{}",
                        slug, pos.getX(), pos.getY(), pos.getZ());
            }
            return;
        }
        Text customName = banner.getCustomName();
        if (customName == null) {
            // renamed→plain banner swap at the same spot: drop the stale marker
            if (config.removeMarkerWithBanner()) {
                store.remove(id);
            }
            return;
        }
        String name = customName.getString();
        if (name.isBlank()) {
            return;
        }
        String design = encodeDesign(bannerBlock.getColor(), banner.getPatterns());
        long now = System.currentTimeMillis();
        MapMarker previous = store.byId(id).orElse(null);
        MapMarker marker = new MapMarker(id, slug,
                name.length() > MarkerStore.MAX_NAME_LENGTH
                        ? name.substring(0, MarkerStore.MAX_NAME_LENGTH) : name,
                "banner", pos.getX(), pos.getY(), pos.getZ(),
                null, "banner", null, null, null,
                previous != null ? previous.createdAtEpochMs() : now, now,
                true, MapMarker.SOURCE_BANNER, design);
        if (store.upsertBanner(marker)) {
            ExplorersFriend.LOGGER.info("[ExplorersFriend/Banners] Banner marker '{}' registered at {} {},{},{}",
                    marker.name(), slug, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * Compact, stable design description: {@code base=white;stripe_top:red;...}
     * (pattern asset path + dye color per layer). Drives the server-side icon renderer
     * and design deduplication.
     */
    public static String encodeDesign(DyeColor base, BannerPatternsComponent patterns) {
        StringBuilder out = new StringBuilder("base=").append(base.getName().toLowerCase(Locale.ROOT));
        for (BannerPatternsComponent.Layer layer : patterns.layers()) {
            var assetId = layer.pattern().value().assetId();
            out.append(';').append(assetId.getPath()).append(':')
                    .append(layer.color().getName().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
