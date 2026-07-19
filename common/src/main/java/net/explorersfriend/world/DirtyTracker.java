package net.explorersfriend.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects "this chunk changed" signals from the block-change mixin and chunk events,
 * and releases them once they have been quiet for the debounce period (or waited
 * longer than the max delay — continuously changing chunks still render).
 *
 * <p>Marking is O(1) on the caller thread (server thread) with a single map entry
 * allocation on first touch. Draining runs on the EF-Sched thread; per-key compute
 * atomicity ensures no update is lost between check and removal.</p>
 */
public final class DirtyTracker {

    /** A released dirty chunk. */
    public record DirtyChunk(String dimensionSlug, int chunkX, int chunkZ) {
    }

    /** value: {firstMarkedNanos, lastMarkedNanos} — mutated only inside compute(). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, long[]>> byDimension = new ConcurrentHashMap<>();

    public void markDirty(String dimensionSlug, int chunkX, int chunkZ) {
        long now = System.nanoTime();
        byDimension.computeIfAbsent(dimensionSlug, k -> new ConcurrentHashMap<>())
                .compute(pack(chunkX, chunkZ), (k, value) -> {
                    if (value == null) {
                        return new long[]{now, now};
                    }
                    value[1] = now;
                    return value;
                });
    }

    /** Removes the mark (e.g. after an unload snapshot already rendered the chunk). */
    public boolean clear(String dimensionSlug, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, long[]> chunks = byDimension.get(dimensionSlug);
        return chunks != null && chunks.remove(pack(chunkX, chunkZ)) != null;
    }

    public boolean isDirty(String dimensionSlug, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, long[]> chunks = byDimension.get(dimensionSlug);
        return chunks != null && chunks.containsKey(pack(chunkX, chunkZ));
    }

    /**
     * Atomically removes and returns all chunks whose quiet period elapsed or whose
     * total wait exceeded the max delay.
     */
    public List<DirtyChunk> drainReady(long debounceMillis, long maxDelayMillis, int limit) {
        long now = System.nanoTime();
        long debounceNanos = debounceMillis * 1_000_000L;
        long maxDelayNanos = maxDelayMillis * 1_000_000L;
        List<DirtyChunk> ready = new ArrayList<>();
        for (var dimEntry : byDimension.entrySet()) {
            if (ready.size() >= limit) {
                break;
            }
            ConcurrentHashMap<Long, long[]> chunks = dimEntry.getValue();
            for (Long key : chunks.keySet()) {
                if (ready.size() >= limit) {
                    break;
                }
                chunks.computeIfPresent(key, (k, value) -> {
                    boolean quiet = now - value[1] >= debounceNanos;
                    boolean overdue = now - value[0] >= maxDelayNanos;
                    if (quiet || overdue) {
                        ready.add(new DirtyChunk(dimEntry.getKey(), unpackX(k), unpackZ(k)));
                        return null; // remove atomically with the check
                    }
                    return value;
                });
            }
        }
        return ready;
    }

    public int size() {
        int total = 0;
        for (ConcurrentHashMap<Long, long[]> chunks : byDimension.values()) {
            total += chunks.size();
        }
        return total;
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
