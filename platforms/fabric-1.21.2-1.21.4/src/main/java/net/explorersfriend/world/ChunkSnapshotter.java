package net.explorersfriend.world;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.render.RenderScheduler;
import net.explorersfriend.render.TileChunkData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The only component that reads live world data — and only on the server thread,
 * under a hard budget: at most {@code maxSnapshotsPerTick} chunk snapshots and
 * {@code tickBudgetMicros} microseconds per tick, whichever is hit first. Requests
 * come from the EF-Sched thread (debounced dirty chunks); results go straight to the
 * render scheduler.
 *
 * <p>Chunks that are no longer loaded are never loaded for the map: their region tile
 * is instead rendered from disk. Queue access is guarded by {@code this}; the queue is
 * bounded and deduplicated.</p>
 */
public final class ChunkSnapshotter {

    private static final int MAX_QUEUE = 8192;

    private record Pending(ServerWorld world, String slug, int chunkX, int chunkZ) {
    }

    private final LiveChunkExtractor extractor;
    private final RenderScheduler scheduler;
    private final Function<ServerWorld, String> slugOf;
    private final int maxSnapshotsPerTick;
    private final long tickBudgetNanos;

    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final AtomicLong snapshotsTaken = new AtomicLong();
    private final AtomicLong snapshotNanos = new AtomicLong();
    private final AtomicLong diskFallbacks = new AtomicLong();

    public ChunkSnapshotter(LiveChunkExtractor extractor, RenderScheduler scheduler,
                            Function<ServerWorld, String> slugOf,
                            int maxSnapshotsPerTick, int tickBudgetMicros) {
        this.extractor = extractor;
        this.scheduler = scheduler;
        this.slugOf = slugOf;
        this.maxSnapshotsPerTick = maxSnapshotsPerTick;
        this.tickBudgetNanos = tickBudgetMicros * 1000L;
    }

    /** Thread-safe enqueue (EF-Sched thread). Silently drops beyond the hard cap. */
    public synchronized void request(ServerWorld world, int chunkX, int chunkZ) {
        if (queue.size() >= MAX_QUEUE) {
            return; // dirty flag persists in the tracker; retried later
        }
        String slug = slugOf.apply(world);
        if (slug == null) {
            return;
        }
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL) ^ ((long) slug.hashCode() << 17);
        if (queued.add(key)) {
            queue.addLast(new Pending(world, slug, chunkX, chunkZ));
        }
    }

    /** END_SERVER_TICK: drain under budget. Runs on the server thread. */
    public void onEndTick(MinecraftServer server) {
        if (isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        int taken = 0;
        while (taken < maxSnapshotsPerTick && System.nanoTime() - start < tickBudgetNanos) {
            Pending pending = pollOne();
            if (pending == null) {
                break;
            }
            snapshotOrFallback(pending);
            taken++;
        }
        if (taken > 0) {
            snapshotNanos.addAndGet(System.nanoTime() - start);
        }
    }

    private synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    private synchronized Pending pollOne() {
        Pending pending = queue.pollFirst();
        if (pending != null) {
            long key = (((long) pending.chunkX()) << 32) ^ (pending.chunkZ() & 0xFFFFFFFFL)
                    ^ ((long) pending.slug().hashCode() << 17);
            queued.remove(key);
        }
        return pending;
    }

    private void snapshotOrFallback(Pending pending) {
        Chunk chunk = pending.world().getChunk(pending.chunkX(), pending.chunkZ(), ChunkStatus.FULL, false);
        if (chunk instanceof WorldChunk worldChunk) {
            TileChunkData data = extractor.extract(pending.world(), worldChunk);
            scheduler.submitChunkPatch(pending.slug(), data);
            snapshotsTaken.incrementAndGet();
        } else {
            // Unloaded: render its whole region from the saved file instead (worker-side).
            diskFallbacks.incrementAndGet();
            scheduler.submitRegionRender(pending.slug(),
                    pending.chunkX() >> 5, pending.chunkZ() >> 5, RenderScheduler.PRIORITY_LIVE);
        }
    }

    /** Direct snapshot on chunk unload (server thread) for still-dirty chunks. */
    public void snapshotNow(ServerWorld world, WorldChunk chunk) {
        String slug = slugOf.apply(world);
        if (slug == null) {
            return;
        }
        try {
            TileChunkData data = extractor.extract(world, chunk);
            scheduler.submitChunkPatch(slug, data);
            snapshotsTaken.incrementAndGet();
        } catch (Exception e) {
            ExplorersFriend.LOGGER.debug("[ExplorersFriend/Renderer] Unload snapshot failed for {} {},{}: {}",
                    slug, chunk.getPos().x, chunk.getPos().z, e.toString());
        }
    }

    public Map<String, Long> statsSnapshot() {
        return Map.of(
                "snapshotsTaken", snapshotsTaken.get(),
                "snapshotNanos", snapshotNanos.get(),
                "diskFallbacks", diskFallbacks.get(),
                "queued", (long) queueSize());
    }

    public synchronized int queueSize() {
        return queue.size();
    }
}
