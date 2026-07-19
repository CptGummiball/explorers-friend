package net.explorersfriend.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.api.ExplorersFriendApi;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.render.TileStore;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live player layer v2: revisioned, delta-capable position publishing.
 *
 * <p>Sampling runs on the server thread at the configured interval (cheap boolean
 * filters only — no IO, no skin work, no JSON). Snapshot/delta state is swapped as one
 * immutable object; HTTP threads build responses from it without ever touching live
 * entities. Positions change revision only when a player actually moved beyond the
 * rounding grid, so an idle server produces 304s. An optional publish delay
 * (anti-stalking) serves positions from the past.</p>
 */
public final class LivePlayerService {

    /** One published player position (already rounded/filtered). */
    public record Point(String uuid, String name, String world, int x, int y, int z, int yaw,
                        long changedAtRevision) {
    }

    /** Immutable published state. */
    public record Snapshot(long revision, List<Point> players, Map<String, Long> removedAtRevision) {
        public static final Snapshot EMPTY = new Snapshot(0, List.of(), Map.of());
    }

    private final MapConfig.Players config;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final Map<String, Point> lastByUuid = new HashMap<>(); // sampler-thread only
    private final ArrayDeque<long[]> delayBufferMeta = new ArrayDeque<>(); // {timestampMs}
    private final ArrayDeque<List<Point>> delayBuffer = new ArrayDeque<>();
    private final ConcurrentHashMap<String, Integer> anonymousNumbers = new ConcurrentHashMap<>();

    public LivePlayerService(MapConfig.Players config) {
        this.config = config;
    }

    /** Runs ON the server thread. Collects and filters; state math happens inline (cheap). */
    public void sample(MinecraftServer server, Set<String> enabledSlugs) {
        if (!config.show()) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        int rounding = Math.max(1, config.positionRounding());
        List<Point> raw = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isDisplayable(player)) {
                continue;
            }
            String slug = TileStore.dimensionSlug(player.getWorld().getRegistryKey().getValue().toString());
            if (!enabledSlugs.contains(slug) || config.disabledWorlds().contains(slug)) {
                continue;
            }
            String uuid = player.getUuidAsString();
            String name = config.anonymizeNames() ? anonymousName(uuid)
                    : player.getGameProfile().getName();
            raw.add(new Point(uuid, name, slug,
                    Math.floorDiv((int) Math.floor(player.getX()), rounding) * rounding,
                    (int) Math.floor(player.getY()),
                    Math.floorDiv((int) Math.floor(player.getZ()), rounding) * rounding,
                    Math.round(player.getYaw()), 0));
        }
        publish(applyDelay(raw));
    }

    private boolean isDisplayable(ServerPlayerEntity player) {
        if (config.hideSpectators() && player.isSpectator()) {
            return false;
        }
        if (config.hideInvisible()
                && (player.isInvisible() || player.hasStatusEffect(StatusEffects.INVISIBILITY))) {
            return false;
        }
        String name = player.getGameProfile().getName();
        String uuid = player.getUuidAsString();
        for (String hidden : config.hiddenPlayers()) {
            if (hidden.equalsIgnoreCase(name) || hidden.equalsIgnoreCase(uuid)) {
                return false;
            }
        }
        // extension point: vanish mods etc. — any veto hides the player
        for (ExplorersFriendApi.PlayerVisibilityProvider provider
                : ExplorersFriendApi.playerVisibilityProviders()) {
            try {
                if (!provider.shouldDisplay(player)) {
                    return false;
                }
            } catch (Exception ignored) {
                // a broken provider must not expose or crash anything; hide defensively
                return false;
            }
        }
        return true;
    }

    /** Anti-stalking delay: serve the newest sample older than the configured delay. */
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
        long revision = previous.revision();
        boolean changed = false;
        Map<String, Point> next = new HashMap<>();
        List<Point> out = new ArrayList<>(current.size());
        long candidateRevision = revision + 1;
        for (Point point : current) {
            Point last = lastByUuid.get(point.uuid());
            boolean moved = last == null
                    || last.x() != point.x() || last.z() != point.z() || last.y() != point.y()
                    || !last.world().equals(point.world()) || last.yaw() != point.yaw()
                    || !last.name().equals(point.name());
            Point published = moved
                    ? new Point(point.uuid(), point.name(), point.world(), point.x(), point.y(),
                    point.z(), point.yaw(), candidateRevision)
                    : last;
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
        // bound removal history
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

    private String anonymousName(String uuid) {
        return "Player " + anonymousNumbers.computeIfAbsent(uuid, k -> anonymousNumbers.size() + 1);
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
