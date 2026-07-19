package net.explorersfriend.overlay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe store for one overlay layer: immutable copy-on-write state with a
 * monotonically increasing revision and a per-dimension spatial grid index
 * (512-block cells) for bounding-box queries.
 *
 * <p>Writers (claim manager, marker store, …) call {@link #applyChanges}; readers
 * (HTTP threads) only ever see fully built immutable snapshots — no Minecraft
 * objects, no locks on the read path. The revision only advances when content
 * actually changed, which drives ETag-based {@code 304 Not Modified} responses.</p>
 */
public final class OverlayLayer<T extends OverlayItem> {

    private static final int CELL_SHIFT = 9; // 512-block cells

    private record State<T extends OverlayItem>(
            long revision,
            Map<String, T> byId,
            Map<String, Map<Long, List<T>>> gridByDimension) {
    }

    private final String layerId;
    private volatile State<T> state = new State<>(0, Map.of(), Map.of());

    public OverlayLayer(String layerId) {
        this.layerId = layerId;
    }

    public String layerId() {
        return layerId;
    }

    public long revision() {
        return state.revision();
    }

    public int size() {
        return state.byId().size();
    }

    /**
     * Applies a batch of upserts and removals atomically. Returns true (and bumps the
     * revision) only when the visible content actually changed. Synchronized against
     * concurrent writers; readers are never blocked.
     */
    public synchronized boolean applyChanges(Collection<T> upserts, Collection<String> removeIds) {
        State<T> current = state;
        boolean changed = false;
        Map<String, T> byId = new HashMap<>(current.byId());
        for (String id : removeIds) {
            if (byId.remove(id) != null) {
                changed = true;
            }
        }
        for (T item : upserts) {
            T previous = byId.put(item.id(), item);
            if (previous == null || !previous.equals(item)) {
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        state = new State<>(current.revision() + 1, Map.copyOf(byId), buildGrid(byId.values()));
        return true;
    }

    /** Replaces the full content (used by full syncs). Bumps revision only on change. */
    public synchronized boolean replaceAll(Collection<T> items) {
        State<T> current = state;
        Map<String, T> byId = new HashMap<>();
        for (T item : items) {
            byId.put(item.id(), item);
        }
        if (byId.equals(current.byId())) {
            return false;
        }
        state = new State<>(current.revision() + 1, Map.copyOf(byId), buildGrid(byId.values()));
        return true;
    }

    private static <T extends OverlayItem> Map<String, Map<Long, List<T>>> buildGrid(Collection<T> items) {
        Map<String, Map<Long, List<T>>> grid = new HashMap<>();
        for (T item : items) {
            Map<Long, List<T>> cells = grid.computeIfAbsent(item.dimensionSlug(), k -> new HashMap<>());
            int minCellX = item.minX() >> CELL_SHIFT;
            int maxCellX = item.maxX() >> CELL_SHIFT;
            int minCellZ = item.minZ() >> CELL_SHIFT;
            int maxCellZ = item.maxZ() >> CELL_SHIFT;
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    cells.computeIfAbsent(cellKey(cellX, cellZ), k -> new ArrayList<>()).add(item);
                }
            }
        }
        return grid;
    }

    /** All items of one dimension (bounded by {@code limit}). */
    public List<T> queryAll(String dimensionSlug, int limit) {
        State<T> snapshot = state;
        List<T> out = new ArrayList<>();
        for (T item : snapshot.byId().values()) {
            if (item.dimensionSlug().equals(dimensionSlug)) {
                out.add(item);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** Items of one dimension intersecting the block-coordinate bounding box. */
    public List<T> queryBox(String dimensionSlug, int minX, int minZ, int maxX, int maxZ, int limit) {
        State<T> snapshot = state;
        Map<Long, List<T>> cells = snapshot.gridByDimension().get(dimensionSlug);
        if (cells == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<T> out = new ArrayList<>();
        int minCellX = minX >> CELL_SHIFT;
        int maxCellX = maxX >> CELL_SHIFT;
        int minCellZ = minZ >> CELL_SHIFT;
        int maxCellZ = maxZ >> CELL_SHIFT;
        for (int cellZ = minCellZ; cellZ <= maxCellZ && out.size() < limit; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX && out.size() < limit; cellX++) {
                List<T> bucket = cells.get(cellKey(cellX, cellZ));
                if (bucket == null) {
                    continue;
                }
                for (T item : bucket) {
                    if (item.maxX() < minX || item.minX() > maxX
                            || item.maxZ() < minZ || item.minZ() > maxZ) {
                        continue; // in cell but outside the exact box
                    }
                    if (seen.add(item.id())) {
                        out.add(item);
                        if (out.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }
}
