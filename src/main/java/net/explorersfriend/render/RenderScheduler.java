package net.explorersfriend.render;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.region.RegionFileReader;
import net.explorersfriend.util.NamedThreadFactory;
import net.explorersfriend.util.RateLimitedLog;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The render work queue: bounded, priority-ordered, with per-tile job merging.
 *
 * <p>Job identity is a {@link TileKey}; submitting work for a key that is already
 * queued merges into the pending job instead of duplicating it (many block changes in
 * one region → one render). Priorities: 0 live updates, 1 zoom updates from live
 * changes, 2 full-render base tiles, 3 full-render zoom updates. Backpressure: when
 * the queue is full, droppable submissions (full-render backlog) are rejected so the
 * producer throttles itself; live updates are always accepted.</p>
 *
 * <p>Workers park on the queue (no busy-wait), honour a cooperative pause, retry a
 * failed tile up to {@value #MAX_ATTEMPTS} times with backoff, and never let one broken
 * tile stop the queue. {@link #close()} drains nothing: it stops accepting work,
 * interrupts workers, and waits bounded time.</p>
 */
public final class RenderScheduler implements AutoCloseable {

    public static final int PRIORITY_LIVE = 0;
    public static final int PRIORITY_LIVE_ZOOM = 1;
    public static final int PRIORITY_BACKLOG = 2;
    public static final int PRIORITY_BACKLOG_ZOOM = 3;

    private static final int MAX_ATTEMPTS = 3;
    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    /** Aggregate counters for status command / API. */
    public record Stats(int queued, boolean paused, long tilesRendered, long tilesFailed,
                        long chunksRendered, double avgTileMillis) {
    }

    private static final class PendingJob {
        final Map<Long, TileChunkData> patches = new HashMap<>();
        boolean fullRegion;
        int priority;
        int attempts;

        PendingJob(int priority) {
            this.priority = priority;
        }
    }

    private record QueueEntry(int priority, long sequence, TileKey key) implements Comparable<QueueEntry> {
        @Override
        public int compareTo(QueueEntry other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    private final TileStore store;
    private final TileRenderer renderer;
    private final RenderedChunksIndex renderedIndex;
    private final Function<String, DimensionContext> contexts;
    private final ScheduledExecutorService retryExecutor;
    private final int maxQueued;
    private final int zoomLevels;

    private final ConcurrentHashMap<TileKey, PendingJob> pending = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<QueueEntry> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final List<Thread> workers = new ArrayList<>();
    private final CountDownLatch stopped;
    private final Object pauseLock = new Object();
    private volatile boolean paused;
    private volatile boolean running = true;
    private final java.util.concurrent.CopyOnWriteArrayList<BiConsumer<TileKey, Boolean>> completionListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final AtomicLong tilesRendered = new AtomicLong();
    private final AtomicLong tilesFailed = new AtomicLong();
    private final AtomicLong chunksRendered = new AtomicLong();
    private final AtomicLong renderNanos = new AtomicLong();
    private final ConcurrentHashMap<Thread, long[]> activeSince = new ConcurrentHashMap<>();
    private final RateLimitedLog warnLog = new RateLimitedLog();

    public RenderScheduler(TileStore store, TileRenderer renderer, RenderedChunksIndex renderedIndex,
                           Function<String, DimensionContext> contexts, ScheduledExecutorService retryExecutor,
                           int workerCount, int maxQueued, int zoomLevels) {
        this.store = store;
        this.renderer = renderer;
        this.renderedIndex = renderedIndex;
        this.contexts = contexts;
        this.retryExecutor = retryExecutor;
        this.maxQueued = maxQueued;
        this.zoomLevels = zoomLevels;
        this.stopped = new CountDownLatch(workerCount);
        NamedThreadFactory factory = new NamedThreadFactory("EF-Render");
        for (int i = 0; i < workerCount; i++) {
            Thread thread = factory.newThread(this::workerLoop);
            workers.add(thread);
            thread.start();
        }
    }

    /** Invoked after every finished base/zoom tile (success flag); used by the full render and the API. */
    public void addCompletionListener(BiConsumer<TileKey, Boolean> listener) {
        completionListeners.add(listener);
    }

    /** Live chunk update; always accepted (bounded in practice by debounce + world size). */
    public void submitChunkPatch(String dimensionSlug, TileChunkData data) {
        if (!running) {
            return;
        }
        TileKey key = TileKey.baseForChunk(dimensionSlug, data.chunkX(), data.chunkZ());
        long chunkKey = (((long) data.chunkX()) << 32) ^ (data.chunkZ() & 0xFFFFFFFFL);
        enqueue(key, PRIORITY_LIVE, false, job -> job.patches.put(chunkKey, data));
        if (pending.size() > maxQueued * 2 && warnLog.shouldLog("queue-overfull", 30_000)) {
            LOGGER.warn("[ExplorersFriend/Renderer] Render queue holds {} jobs (limit {}); "
                    + "consider more render workers or a smaller update rate", pending.size(), maxQueued);
        }
    }

    /**
     * Whole-region render from disk. @return false when the queue is saturated
     * (producer should retry later).
     */
    public boolean submitRegionRender(String dimensionSlug, int regionX, int regionZ, int priority) {
        if (!running) {
            return false;
        }
        boolean droppable = priority >= PRIORITY_BACKLOG;
        if (droppable && pending.size() >= maxQueued) {
            return false;
        }
        TileKey key = TileKey.baseForRegion(dimensionSlug, regionX, regionZ);
        enqueue(key, priority, false, job -> job.fullRegion = true);
        return true;
    }

    private void submitZoom(TileKey zoomKey, int priority) {
        enqueue(zoomKey, priority, false, job -> {
        });
    }

    private void enqueue(TileKey key, int priority, boolean ignoredDroppable,
                         java.util.function.Consumer<PendingJob> merger) {
        boolean[] requeue = {false};
        pending.compute(key, (k, existing) -> {
            PendingJob job = existing != null ? existing : new PendingJob(priority);
            if (existing == null || priority < job.priority) {
                job.priority = Math.min(job.priority, priority);
                requeue[0] = true;
            }
            merger.accept(job);
            return job;
        });
        if (requeue[0]) {
            queue.add(new QueueEntry(priority, sequence.getAndIncrement(), key));
        }
    }

    // --- worker side -------------------------------------------------------

    private void workerLoop() {
        int[] pixels = new int[TileRenderer.TILE_SIZE * TileRenderer.TILE_SIZE];
        int[] childPixels = new int[TileRenderer.TILE_SIZE * TileRenderer.TILE_SIZE];
        try {
            while (running) {
                QueueEntry entry;
                try {
                    entry = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue; // running flag decides
                }
                if (entry == null) {
                    continue;
                }
                awaitNotPaused();
                if (!running) {
                    return;
                }
                PendingJob job = pending.remove(entry.key());
                if (job == null) {
                    continue; // superseded entry
                }
                executeJob(entry.key(), job, pixels, childPixels);
            }
        } finally {
            stopped.countDown();
        }
    }

    private void awaitNotPaused() {
        if (!paused) {
            return;
        }
        synchronized (pauseLock) {
            while (paused && running) {
                try {
                    pauseLock.wait(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void executeJob(TileKey key, PendingJob job, int[] pixels, int[] childPixels) {
        long start = System.nanoTime();
        activeSince.put(Thread.currentThread(), new long[]{start});
        boolean success = false;
        try {
            if (key.zoom() > 0) {
                executeZoom(key, job.priority, pixels, childPixels);
            } else if (job.fullRegion) {
                executeFullRegion(key, pixels);
            } else {
                executePatch(key, job, pixels);
            }
            success = true;
            tilesRendered.incrementAndGet();
            renderNanos.addAndGet(System.nanoTime() - start);
        } catch (Exception e) {
            job.attempts++;
            if (job.attempts < MAX_ATTEMPTS && running) {
                int attempt = job.attempts;
                retryExecutor.schedule(() -> requeueForRetry(key, job), 2L * attempt, TimeUnit.SECONDS);
                if (warnLog.shouldLog("retry-" + key.dimensionSlug(), 10_000)) {
                    LOGGER.warn("[ExplorersFriend/Renderer] Tile {} failed (attempt {}/{}): {} - retrying",
                            key, attempt, MAX_ATTEMPTS, e.toString());
                }
            } else {
                tilesFailed.incrementAndGet();
                LOGGER.error("[ExplorersFriend/Renderer] Tile {} failed permanently after {} attempts",
                        key, job.attempts, e);
            }
        } finally {
            activeSince.remove(Thread.currentThread());
            for (BiConsumer<TileKey, Boolean> listener : completionListeners) {
                try {
                    listener.accept(key, success);
                } catch (Exception e) {
                    LOGGER.warn("[ExplorersFriend/Renderer] Completion listener threw: {}", e.toString());
                }
            }
        }
    }

    private void requeueForRetry(TileKey key, PendingJob job) {
        if (!running) {
            return;
        }
        pending.merge(key, job, (k, existing) -> {
            existing.patches.putAll(job.patches);
            existing.fullRegion |= job.fullRegion;
            existing.priority = Math.min(existing.priority, job.priority);
            existing.attempts = Math.max(existing.attempts, job.attempts);
            return existing;
        });
        queue.add(new QueueEntry(job.priority, sequence.getAndIncrement(), key));
    }

    private void executePatch(TileKey key, PendingJob job, int[] pixels) throws Exception {
        int[] existing = store.readTile(key.dimensionSlug(), 0, key.tileX(), key.tileZ());
        if (existing != null) {
            System.arraycopy(existing, 0, pixels, 0, pixels.length);
        } else {
            java.util.Arrays.fill(pixels, 0);
        }
        for (TileChunkData data : job.patches.values()) {
            renderer.patchChunk(pixels, data, key.tileX(), key.tileZ());
        }
        store.writeTile(key.dimensionSlug(), 0, key.tileX(), key.tileZ(), pixels);
        for (TileChunkData data : job.patches.values()) {
            renderedIndex.markRendered(key.dimensionSlug(), data.chunkX(), data.chunkZ());
        }
        chunksRendered.addAndGet(job.patches.size());
        submitZoom(key.parent(), PRIORITY_LIVE_ZOOM);
    }

    private void executeFullRegion(TileKey key, int[] pixels) throws Exception {
        DimensionContext context = contexts.apply(key.dimensionSlug());
        if (context == null) {
            throw new IllegalStateException("no dimension context for " + key.dimensionSlug());
        }
        Path regionFile = context.regionDir().resolve("r." + key.tileX() + "." + key.tileZ() + ".mca");
        if (!Files.exists(regionFile)) {
            // Region vanished (deleted world part): drop the tile if present.
            java.util.Arrays.fill(pixels, 0);
            store.writeTile(key.dimensionSlug(), 0, key.tileX(), key.tileZ(), pixels);
            submitZoom(key.parent(), PRIORITY_BACKLOG_ZOOM);
            return;
        }
        RegionFileReader reader = new RegionFileReader(regionFile);
        List<TileChunkData> chunks = new ArrayList<>();
        int chunkErrors = 0;
        for (int[] position : reader.presentChunks()) {
            try {
                Map<String, Object> chunkRoot = reader.readChunk(position[0], position[1]);
                if (chunkRoot == null) {
                    continue;
                }
                TileChunkData data = context.extractor().extract(chunkRoot);
                if (data != null) {
                    chunks.add(data);
                }
            } catch (Exception e) {
                chunkErrors++; // isolate: one broken chunk must not kill the tile
            }
        }
        if (chunkErrors > 0 && warnLog.shouldLog("chunk-errors-" + key.dimensionSlug(), 30_000)) {
            LOGGER.warn("[ExplorersFriend/Renderer] {}: {} unreadable chunk(s) in {} (skipped, will retry on change)",
                    key.dimensionSlug(), chunkErrors, regionFile.getFileName());
        }
        renderer.renderRegion(chunks, key.tileX(), key.tileZ(), pixels);
        store.writeTile(key.dimensionSlug(), 0, key.tileX(), key.tileZ(), pixels);
        for (TileChunkData data : chunks) {
            renderedIndex.markRendered(key.dimensionSlug(), data.chunkX(), data.chunkZ());
        }
        chunksRendered.addAndGet(chunks.size());
        submitZoom(key.parent(), PRIORITY_BACKLOG_ZOOM);
    }

    private void executeZoom(TileKey key, int jobPriority, int[] pixels, int[] childPixels) throws Exception {
        java.util.Arrays.fill(pixels, 0);
        int childZoom = key.zoom() - 1;
        boolean any = false;
        for (int quadZ = 0; quadZ < 2; quadZ++) {
            for (int quadX = 0; quadX < 2; quadX++) {
                int childX = key.tileX() * 2 + quadX;
                int childZ = key.tileZ() * 2 + quadZ;
                int[] child = store.readTile(key.dimensionSlug(), childZoom, childX, childZ);
                if (child == null) {
                    continue;
                }
                System.arraycopy(child, 0, childPixels, 0, child.length);
                TilePyramid.downsampleInto(childPixels, pixels, quadX, quadZ);
                any = true;
            }
        }
        store.writeTile(key.dimensionSlug(), key.zoom(), key.tileX(), key.tileZ(), pixels);
        if (any && key.zoom() < zoomLevels) {
            submitZoom(key.parent(),
                    jobPriority <= PRIORITY_LIVE_ZOOM ? PRIORITY_LIVE_ZOOM : PRIORITY_BACKLOG_ZOOM);
        }
    }

    // --- control -----------------------------------------------------------

    public void pause() {
        paused = true;
        LOGGER.info("[ExplorersFriend/Renderer] Rendering paused");
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        LOGGER.info("[ExplorersFriend/Renderer] Rendering resumed");
    }

    public boolean isPaused() {
        return paused;
    }

    /** Drops all queued full-render backlog jobs (live updates stay). */
    public int cancelBacklog(String dimensionSlugOrNull) {
        int removed = 0;
        for (Map.Entry<TileKey, PendingJob> entry : pending.entrySet()) {
            TileKey key = entry.getKey();
            PendingJob job = entry.getValue();
            boolean matchesDim = dimensionSlugOrNull == null || key.dimensionSlug().equals(dimensionSlugOrNull);
            if (matchesDim && job.priority >= PRIORITY_BACKLOG && pending.remove(key, job)) {
                removed++;
            }
        }
        return removed;
    }

    public int queuedJobs() {
        return pending.size();
    }

    public Stats stats() {
        long rendered = tilesRendered.get();
        double avgMillis = rendered == 0 ? 0 : renderNanos.get() / 1_000_000.0 / rendered;
        return new Stats(pending.size(), paused, rendered, tilesFailed.get(), chunksRendered.get(), avgMillis);
    }

    /** Watchdog hook: WARNs when a worker has been stuck on one tile for too long. */
    public void checkStuckWorkers(long thresholdMillis) {
        long now = System.nanoTime();
        for (Map.Entry<Thread, long[]> entry : activeSince.entrySet()) {
            long runningMillis = (now - entry.getValue()[0]) / 1_000_000;
            if (runningMillis > thresholdMillis && warnLog.shouldLog("stuck-" + entry.getKey().getName(), 60_000)) {
                LOGGER.warn("[ExplorersFriend/Renderer] Worker {} has been rendering one tile for {} ms",
                        entry.getKey().getName(), runningMillis);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        try {
            if (!stopped.await(10, TimeUnit.SECONDS)) {
                LOGGER.warn("[ExplorersFriend/Renderer] {} worker(s) did not stop within 10 s (daemon threads, "
                        + "JVM exit is not blocked)", stopped.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pending.clear();
        queue.clear();
    }
}
