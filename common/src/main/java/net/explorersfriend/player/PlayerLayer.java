package net.explorersfriend.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.config.MapConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft-independent core of the live player layer: revisioned snapshot/delta
 * state, anti-stalking delay buffer, name anonymization and the HTTP response model.
 * The platform module samples entities on the game thread and feeds plain
 * {@link Point}s in; HTTP threads only ever read immutable {@link Snapshot}s.
 */
public final class PlayerLayer {

    /** One published player position (already rounded/filtered by the sampler). */
    public record Point(String uuid, String name, String world, int x, int y, int z, int yaw,
                        long changedAtRevision) {

        public Point moved(long revision) {
            return new Point(uuid, name, world, x, y, z, yaw, revision);
        }
    }

    /** Immutable published state. */
    public record Snapshot(long revision, List<Point> players, Map<String, Long> removedAtRevision) {
        public static final Snapshot EMPTY = new Snapshot(0, List.of(), Map.of());
    }

    private final MapConfig.Players config;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final Map<String, Point> lastByUuid = new HashMap<>(); // sampler-thread only
    private final ArrayDeque<long[]> delayBufferMeta = new ArrayDeque<>();
    private final ArrayDeque<List<Point>> delayBuffer = new ArrayDeque<>();
    private final ConcurrentHashMap<String, Integer> anonymousNumbers = new ConcurrentHashMap<>();

    public PlayerLayer(MapConfig.Players config) {
        this.config = config;
    }

    /** Applies anonymization when configured; stable per session and uuid. */
    public String displayName(String uuid, String realName) {
        if (!config.anonymizeNames()) {
            return realName;
        }
        return "Player " + anonymousNumbers.computeIfAbsent(uuid, k -> anonymousNumbers.size() + 1);
    }

    /** Sampler entry point (single-threaded): delay, diff, publish. */
    public void publishSample(List<Point> current) {
        publish(applyDelay(current));
    }

    private List<Point> applyDelay(List<Point> current) {
        int delaySeconds = config.positionDelaySeconds();
        if (delaySeconds <= 0) {
            return current;
        }
        long now = System.currentTimeMillis();
        delayBuffer.addLast(current);
        delayBufferMeta.addLast(new long[]{now});
        List<Point> published = List.of();
        while (!delayBufferMeta.isEmpty() && now - delayBufferMeta.peekFirst()[0] >= delaySeconds * 1000L) {
            delayBufferMeta.pollFirst();
            published = delayBuffer.pollFirst();
        }
        return published;
    }

    private void publish(List<Point> current) {
        Snapshot previous = snapshot;
        boolean changed = false;
        Map<String, Point> next = new HashMap<>();
        List<Point> out = new ArrayList<>(current.size());
        long candidateRevision = previous.revision() + 1;
        for (Point point : current) {
            Point last = lastByUuid.get(point.uuid());
            boolean moved = last == null
                    || last.x() != point.x() || last.z() != point.z() || last.y() != point.y()
                    || !last.world().equals(point.world()) || last.yaw() != point.yaw()
                    || !last.name().equals(point.name());
            Point published = moved ? point.moved(candidateRevision) : last;
            if (moved) {
                changed = true;
            }
            next.put(point.uuid(), published);
            out.add(published);
        }
        Map<String, Long> removed = new HashMap<>(previous.removedAtRevision());
        for (String uuid : lastByUuid.keySet()) {
            if (!next.containsKey(uuid)) {
                removed.put(uuid, candidateRevision);
                changed = true;
            }
        }
        if (removed.size() > 256) {
            long cutoff = candidateRevision - 512;
            removed.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        lastByUuid.clear();
        lastByUuid.putAll(next);
        if (changed) {
            snapshot = new Snapshot(candidateRevision, List.copyOf(out), Map.copyOf(removed));
        }
    }

    public Snapshot current() {
        return snapshot;
    }

    public void clear() {
        snapshot = Snapshot.EMPTY;
    }

    /** Response for {@code /api/v1/players}: full or delta (since &gt; 0), world-filtered. */
    public JsonObject buildResponse(String worldSlugOrNull, long since) {
        Snapshot snap = snapshot;
        JsonObject root = new JsonObject();
        root.addProperty("revision", snap.revision());
        root.addProperty("interval", config.updateIntervalSeconds());
        JsonArray players = new JsonArray();
        for (Point point : snap.players()) {
            if (worldSlugOrNull != null && !point.world().equals(worldSlugOrNull)) {
                continue;
            }
            if (since > 0 && point.changedAtRevision() <= since) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", point.uuid());
            if (config.showNames()) {
                entry.addProperty("name", point.name());
            }
            entry.addProperty("world", point.world());
            entry.addProperty("x", point.x());
            if (config.showCoordinates()) {
                entry.addProperty("y", point.y());
            }
            entry.addProperty("z", point.z());
            entry.addProperty("yaw", point.yaw());
            players.add(entry);
        }
        root.add(since > 0 ? "changed" : "players", players);
        if (since > 0) {
            JsonArray removed = new JsonArray();
            for (Map.Entry<String, Long> entry : snap.removedAtRevision().entrySet()) {
                if (entry.getValue() > since) {
                    removed.add(entry.getKey());
                }
            }
            root.add("removed", removed);
            root.addProperty("delta", true);
        }
        return root;
    }
}
