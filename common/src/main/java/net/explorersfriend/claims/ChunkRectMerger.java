package net.explorersfriend.claims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy rectangle merging for chunk-based claims: consecutive chunks in a row become
 * horizontal strips, identical strips in adjacent rows merge vertically. Thousands of
 * claimed chunks typically collapse into a handful of rectangles; disjoint areas and
 * enclaves fall out naturally as separate rectangles. Pure and deterministic.
 */
public final class ChunkRectMerger {

    private ChunkRectMerger() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** @param packedChunks chunk positions packed via {@link #pack} */
    public static List<MapClaim.ClaimRect> merge(Collection<Long> packedChunks) {
        // group by row (z), sort x runs
        Map<Integer, List<Integer>> rows = new HashMap<>();
        for (long packed : packedChunks) {
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            rows.computeIfAbsent(chunkZ, k -> new ArrayList<>()).add(chunkX);
        }
        record Strip(int minX, int maxX) {
        }
        Map<Integer, List<Strip>> stripsByRow = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> row : rows.entrySet()) {
            List<Integer> xs = row.getValue();
            xs.sort(Integer::compare);
            List<Strip> strips = new ArrayList<>();
            int runStart = xs.get(0);
            int previous = runStart;
            for (int i = 1; i < xs.size(); i++) {
                int x = xs.get(i);
                if (x == previous) {
                    continue;
                }
                if (x != previous + 1) {
                    strips.add(new Strip(runStart, previous));
                    runStart = x;
                }
                previous = x;
            }
            strips.add(new Strip(runStart, previous));
            stripsByRow.put(row.getKey(), strips);
        }

        // merge identical strips across adjacent rows
        List<MapClaim.ClaimRect> rects = new ArrayList<>();
        List<Integer> sortedRows = new ArrayList<>(stripsByRow.keySet());
        sortedRows.sort(Integer::compare);
        record OpenRect(int minX, int maxX, int minZ) {
        }
        Map<Long, OpenRect> open = new HashMap<>(); // key: minX,maxX packed
        int previousRow = Integer.MIN_VALUE;
        for (int rowZ : sortedRows) {
            Map<Long, OpenRect> next = new HashMap<>();
            List<Strip> strips = stripsByRow.get(rowZ);
            for (Strip strip : strips) {
                long key = ((long) strip.minX() << 32) ^ (strip.maxX() & 0xFFFFFFFFL);
                OpenRect continued = rowZ == previousRow + 1 ? open.remove(key) : null;
                next.put(key, continued != null ? continued : new OpenRect(strip.minX(), strip.maxX(), rowZ));
            }
            // strips that did not continue are finished
            for (OpenRect rect : open.values()) {
                rects.add(toBlockRect(rect.minX(), rect.maxX(), rect.minZ(), previousRow));
            }
            open = next;
            previousRow = rowZ;
        }
        for (OpenRect rect : open.values()) {
            rects.add(toBlockRect(rect.minX(), rect.maxX(), rect.minZ(), previousRow));
        }
        rects.sort((a, b) -> a.minZ() != b.minZ() ? Integer.compare(a.minZ(), b.minZ())
                : Integer.compare(a.minX(), b.minX()));
        return rects;
    }

    private static MapClaim.ClaimRect toBlockRect(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        return new MapClaim.ClaimRect(minChunkX << 4, minChunkZ << 4,
                (maxChunkX << 4) + 15, (maxChunkZ << 4) + 15);
    }
}
