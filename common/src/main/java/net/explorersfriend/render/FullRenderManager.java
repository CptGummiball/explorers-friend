package net.explorersfriend.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.util.Log;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;
import net.explorersfriend.util.RateLimitedLog;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives manual/initial full renders: enumerates the region files of a dimension,
 * orders them around a center (spawn), and feeds them into the {@link RenderScheduler}
 * respecting its backpressure. Progress is persisted so an interrupted full render
 * resumes automatically on the next start.
 *
 * <p>All methods are called from the EF-Sched thread or command handlers; internal
 * state is confined to a per-dimension job object guarded by its own monitor.</p>
 */
public final class FullRenderManager {

    private static final Logger LOGGER = Log.LOGGER;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    public static final int PROGRESS_SCHEMA_VERSION = 1;

    /** One running full render. */
    private static final class Job {
        final String dimensionSlug;
        final ArrayDeque<long[]> remaining; // {regionX, regionZ}
        final int total;
        int completed;
        int failed;
        final long startNanos = System.nanoTime();

        Job(String dimensionSlug, ArrayDeque<long[]> remaining, int alreadyCompleted) {
            this.dimensionSlug = dimensionSlug;
            this.remaining = remaining;
            this.total = remaining.size() + alreadyCompleted;
            this.completed = alreadyCompleted;
        }
    }

    private final RenderScheduler scheduler;
    private final Path progressDir;
    private final int maxQueuedTiles;
    private final RateLimitedLog progressLog = new RateLimitedLog();
    private final int progressIntervalSeconds;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public FullRenderManager(RenderScheduler scheduler, Path progressDir,
                             int maxQueuedTiles, int progressIntervalSeconds) {
        this.scheduler = scheduler;
        this.progressDir = progressDir;
        this.maxQueuedTiles = maxQueuedTiles;
        this.progressIntervalSeconds = progressIntervalSeconds;
        scheduler.addCompletionListener(this::onTileComplete);
    }

    /**
     * Starts (or restarts) a full render.
     *
     * @param radiusBlocks 0 = whole explored world, otherwise limit around the center
     * @return number of regions scheduled, or -1 when one is already running
     */
    public int start(DimensionContext context, int centerBlockX, int centerBlockZ, int radiusBlocks) {
        if (jobs.containsKey(context.slug())) {
            return -1;
        }
        List<long[]> regions = enumerateRegions(context.regionDir(), centerBlockX, centerBlockZ, radiusBlocks);
        if (regions.isEmpty()) {
            return 0;
        }
        Job job = new Job(context.slug(), new ArrayDeque<>(regions), 0);
        jobs.put(context.slug(), job);
        persist(job);
        LOGGER.info("[ExplorersFriend/Renderer] Full render of {} started: {} region(s) queued",
                context.dimensionId(), regions.size());
        return regions.size();
    }

    /** Resumes a persisted, interrupted full render if one exists for this dimension. */
    public void resumeIfPersisted(DimensionContext context) {
        Path file = progressFile(context.slug());
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != PROGRESS_SCHEMA_VERSION) {
                Files.deleteIfExists(file);
                return;
            }
            ArrayDeque<long[]> remaining = new ArrayDeque<>();
            for (JsonElement el : root.getAsJsonArray("remaining")) {
                JsonArray pair = el.getAsJsonArray();
                remaining.add(new long[]{pair.get(0).getAsInt(), pair.get(1).getAsInt()});
            }
            int completed = root.get("completed").getAsInt();
            if (remaining.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            jobs.put(context.slug(), new Job(context.slug(), remaining, completed));
            LOGGER.info("[ExplorersFriend/Renderer] Resuming interrupted full render of {}: {} region(s) left",
                    context.dimensionId(), remaining.size());
        } catch (Exception e) {
            LOGGER.warn("[ExplorersFriend/Renderer] Unreadable render progress for {} ({}); starting fresh",
                    context.slug(), e.getMessage());
            MoreFiles.quarantine(file);
        }
    }

    /** Periodic tick from EF-Sched: feed the scheduler while it has capacity. */
    public void feed() {
        for (Job job : jobs.values()) {
            synchronized (job) {
                while (!job.remaining.isEmpty() && scheduler.queuedJobs() < maxQueuedTiles / 2
                        && !scheduler.isPaused()) {
                    long[] region = job.remaining.peekFirst();
                    if (!scheduler.submitRegionRender(job.dimensionSlug, (int) region[0], (int) region[1],
                            RenderScheduler.PRIORITY_BACKLOG)) {
                        break;
                    }
                    job.remaining.pollFirst();
                }
                logProgress(job);
            }
        }
    }

    private void onTileComplete(TileKey key, boolean success) {
        if (key.zoom() != 0) {
            return;
        }
        Job job = jobs.get(key.dimensionSlug());
        if (job == null) {
            return;
        }
        synchronized (job) {
            job.completed++;
            if (!success) {
                job.failed++;
            }
            if (job.completed >= job.total && job.remaining.isEmpty()) {
                jobs.remove(key.dimensionSlug());
                deleteProgress(key.dimensionSlug());
                long seconds = Math.max(1, (System.nanoTime() - job.startNanos) / 1_000_000_000L);
                LOGGER.info("[ExplorersFriend/Renderer] Full render of {} finished: {} region(s) in {} s"
                                + "{}", key.dimensionSlug(), job.total, seconds,
                        job.failed > 0 ? " (" + job.failed + " failed, see warnings)" : "");
            }
        }
    }

    private void logProgress(Job job) {
        if (job.total > 0 && (job.completed < job.total || !job.remaining.isEmpty())
                && progressLog.shouldLog("full-" + job.dimensionSlug, progressIntervalSeconds * 1000L)) {
            long seconds = Math.max(1, (System.nanoTime() - job.startNanos) / 1_000_000_000L);
            double perSecond = job.completed / (double) seconds;
            LOGGER.info("[ExplorersFriend/Renderer] Full render {}: {}/{} regions ({}%), {} regions/s, queue {}",
                    job.dimensionSlug, job.completed, job.total,
                    job.completed * 100 / Math.max(1, job.total),
                    String.format(java.util.Locale.ROOT, "%.1f", perSecond),
                    scheduler.queuedJobs());
        }
    }

    /** Cancels the full render of one dimension (or all when null). @return cancelled regions */
    public int cancel(String dimensionSlugOrNull) {
        int cancelled = 0;
        for (Job job : List.copyOf(jobs.values())) {
            if (dimensionSlugOrNull != null && !job.dimensionSlug.equals(dimensionSlugOrNull)) {
                continue;
            }
            synchronized (job) {
                cancelled += job.remaining.size();
                job.remaining.clear();
            }
            jobs.remove(job.dimensionSlug);
            deleteProgress(job.dimensionSlug);
        }
        cancelled += scheduler.cancelBacklog(dimensionSlugOrNull);
        return cancelled;
    }

    public boolean isActive(String dimensionSlug) {
        return jobs.containsKey(dimensionSlug);
    }

    public Map<String, int[]> progressSnapshot() {
        Map<String, int[]> out = new java.util.HashMap<>();
        for (Job job : jobs.values()) {
            synchronized (job) {
                out.put(job.dimensionSlug, new int[]{job.completed, job.total});
            }
        }
        return out;
    }

    /** Persists remaining work of all active jobs (periodic + shutdown). */
    public void persistAll() {
        for (Job job : jobs.values()) {
            synchronized (job) {
                persist(job);
            }
        }
    }

    private void persist(Job job) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", PROGRESS_SCHEMA_VERSION);
        root.addProperty("completed", job.completed);
        JsonArray remaining = new JsonArray();
        for (long[] region : job.remaining) {
            JsonArray pair = new JsonArray();
            pair.add((int) region[0]);
            pair.add((int) region[1]);
            remaining.add(pair);
        }
        root.add("remaining", remaining);
        try {
            MoreFiles.writeAtomicUtf8(progressFile(job.dimensionSlug), Jsonc.GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("[ExplorersFriend/Renderer] Could not persist render progress for {}: {}",
                    job.dimensionSlug, e.toString());
        }
    }

    private void deleteProgress(String dimensionSlug) {
        try {
            Files.deleteIfExists(progressFile(dimensionSlug));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private Path progressFile(String dimensionSlug) {
        return progressDir.resolve(dimensionSlug + ".json");
    }

    /** Lists regions sorted by distance to the center, optionally radius-limited. */
    public static List<long[]> enumerateRegions(Path regionDir, int centerBlockX, int centerBlockZ, int radiusBlocks) {
        List<long[]> regions = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) {
            return regions;
        }
        int centerRegionX = centerBlockX >> 9;
        int centerRegionZ = centerBlockZ >> 9;
        long radiusRegions = radiusBlocks <= 0 ? Long.MAX_VALUE : (radiusBlocks >> 9) + 1L;
        try (var stream = Files.list(regionDir)) {
            stream.forEach(path -> {
                Matcher matcher = REGION_NAME.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    return;
                }
                try {
                    if (Files.size(path) < 8192) {
                        return; // header only, no chunks
                    }
                } catch (IOException e) {
                    return;
                }
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                if (Math.max(Math.abs(regionX - centerRegionX), Math.abs(regionZ - centerRegionZ)) <= radiusRegions) {
                    regions.add(new long[]{regionX, regionZ});
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[ExplorersFriend/Renderer] Could not list region files in {}: {}", regionDir, e.toString());
        }
        regions.sort(Comparator.comparingLong(region ->
                (region[0] - centerRegionX) * (region[0] - centerRegionX)
                        + (region[1] - centerRegionZ) * (region[1] - centerRegionZ)));
        return regions;
    }
}
