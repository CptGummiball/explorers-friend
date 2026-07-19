package net.explorersfriend.claims;

import net.explorersfriend.util.Log;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.overlay.OverlayLayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Drives all claim providers: schedules per-provider refreshes, debounces change
 * events, diffs results, and publishes them into the {@code claims} overlay layer.
 *
 * <p>Threading: the raw copy runs on the server thread (provider APIs are treated as
 * server-thread-only, the copy is plain data collection); rectangle merging, color
 * resolution, privacy filtering and diffing run on the scan pool. Refresh failures
 * keep the last good data and back off exponentially (up to 8× the base interval).</p>
 */
public final class ClaimManager {

    private static final Logger LOGGER = Log.LOGGER;
    private static final long EVENT_DEBOUNCE_MS = 5000;

    private final OverlayLayer<MapClaim> layer;
    private final List<ClaimProvider> providers;
    private final MapConfig.Claims config;
    private final Executor serverExecutor;
    private final ExecutorService workers;
    private final Function<String, String> dimensionIdToSlug;

    private final Map<String, Set<String>> knownIdsByProvider = new ConcurrentHashMap<>();
    private final Map<String, Integer> backoffMultiplier = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> refreshRunning = new ConcurrentHashMap<>();
    private final Map<String, Long> eventDirtySince = new ConcurrentHashMap<>();

    public ClaimManager(OverlayLayer<MapClaim> layer,
                        List<ClaimProvider> providers,
                        MapConfig.Claims config,
                        Executor serverExecutor,
                        ExecutorService workers,
                        Function<String, String> dimensionIdToSlug) {
        this.layer = layer;
        this.providers = List.copyOf(providers);
        this.config = config;
        this.serverExecutor = serverExecutor;
        this.workers = workers;
        this.dimensionIdToSlug = dimensionIdToSlug;
    }

    /** Registers event subscriptions and schedules the periodic refresh cycle. */
    public void start(ScheduledExecutorService sched) {
        for (ClaimProvider provider : providers) {
            try {
                provider.subscribe(() -> eventDirtySince.putIfAbsent(provider.providerId(),
                        System.nanoTime()));
            } catch (Exception e) {
                LOGGER.warn("[ExplorersFriend/Claims] Could not subscribe to {} events: {}",
                        provider.providerId(), e.toString());
            }
            refresh(provider); // initial full sync
        }
        sched.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
    }

    /** 5-second heartbeat: fires debounced event refreshes and interval refreshes. */
    private void tick() {
        long now = System.nanoTime();
        for (ClaimProvider provider : providers) {
            String id = provider.providerId();
            Long dirtySince = eventDirtySince.get(id);
            boolean eventDue = dirtySince != null && now - dirtySince >= EVENT_DEBOUNCE_MS * 1_000_000L;
            long interval = (long) config.refreshIntervalSeconds() * backoffMultiplier.getOrDefault(id, 1);
            Long lastRefresh = lastRefreshNanos.get(id);
            boolean intervalDue = lastRefresh == null || now - lastRefresh >= interval * 1_000_000_000L;
            if (eventDue || intervalDue) {
                refresh(provider);
            }
        }
    }

    private final Map<String, Long> lastRefreshNanos = new ConcurrentHashMap<>();

    private void refresh(ClaimProvider provider) {
        String id = provider.providerId();
        AtomicBoolean running = refreshRunning.computeIfAbsent(id, k -> new AtomicBoolean());
        if (!running.compareAndSet(false, true)) {
            return; // previous refresh still in flight
        }
        eventDirtySince.remove(id);
        lastRefreshNanos.put(id, System.nanoTime());
        CompletableFuture
                .supplyAsync(() -> {
                    if (!provider.isAvailable()) {
                        return List.<ClaimProvider.RawArea>of();
                    }
                    return provider.copyRawClaims();
                }, serverExecutor)
                .thenApplyAsync(raw -> transform(id, raw), workers)
                .whenComplete((claims, error) -> {
                    try {
                        if (error != null) {
                            int multiplier = Math.min(8, backoffMultiplier.getOrDefault(id, 1) * 2);
                            backoffMultiplier.put(id, multiplier);
                            LOGGER.warn("[ExplorersFriend/Claims] Refresh of {} failed ({}); keeping last data, "
                                    + "next attempt in {} s", id, rootMessage(error),
                                    (long) config.refreshIntervalSeconds() * multiplier);
                            return;
                        }
                        backoffMultiplier.put(id, 1);
                        publish(id, claims);
                    } finally {
                        running.set(false);
                    }
                });
    }

    /** Worker-side: raw areas → merged, colored, privacy-filtered claims. */
    private List<MapClaim> transform(String providerId, List<ClaimProvider.RawArea> rawAreas) {
        List<MapClaim> out = new ArrayList<>(rawAreas.size());
        Set<String> disabledWorlds = new HashSet<>(config.disabledWorlds());
        long now = System.currentTimeMillis();
        for (ClaimProvider.RawArea area : rawAreas) {
            try {
                boolean hasGeometry = !area.chunks().isEmpty()
                        || (area.explicitRects() != null && !area.explicitRects().isEmpty());
                if (area.hidden() || !hasGeometry) {
                    continue;
                }
                if (disabledWorlds.contains(area.dimensionId())) {
                    continue;
                }
                String slug = dimensionIdToSlug.apply(area.dimensionId());
                if (slug == null) {
                    continue; // dimension not mapped
                }
                List<MapClaim.ClaimRect> rects = area.explicitRects() != null && !area.explicitRects().isEmpty()
                        ? area.explicitRects()
                        : ChunkRectMerger.merge(area.chunks());
                String stableKey = area.teamName() != null && !area.teamName().isBlank()
                        ? providerId + ":" + area.teamName()
                        : providerId + ":" + area.areaKey();
                int base = ClaimColors.resolveBase(area.explicitColor(), null, null,
                        stableKey, config.defaultColor());
                out.add(new MapClaim(
                        providerId + ":" + Integer.toHexString(area.areaKey().hashCode())
                                + ":" + slug,
                        providerId,
                        slug,
                        rects,
                        config.showName() ? area.claimName() : null,
                        config.showOwner() ? area.ownerName() : null,
                        config.showTeam() ? area.teamName() : null,
                        ClaimColors.fill(base, config.fillOpacity()),
                        ClaimColors.border(base),
                        now));
            } catch (Exception e) {
                LOGGER.debug("[ExplorersFriend/Claims] Skipping malformed area from {}: {}",
                        providerId, e.toString());
            }
        }
        return out;
    }

    /** Publishes one provider's result, removing its claims that vanished. */
    private void publish(String providerId, List<MapClaim> claims) {
        Set<String> currentIds = new HashSet<>();
        for (MapClaim claim : claims) {
            currentIds.add(claim.id());
        }
        Set<String> previous = knownIdsByProvider.getOrDefault(providerId, Set.of());
        List<String> removed = new ArrayList<>();
        for (String oldId : previous) {
            if (!currentIds.contains(oldId)) {
                removed.add(oldId);
            }
        }
        boolean changed = layer.applyChanges(claims, removed);
        knownIdsByProvider.put(providerId, currentIds);
        if (changed) {
            LOGGER.info("[ExplorersFriend/Claims] Refresh completed: provider={}, {} area(s), {} removed, revision {}",
                    providerId, claims.size(), removed.size(), layer.revision());
        } else {
            LOGGER.debug("[ExplorersFriend/Claims] Refresh completed: provider={}, no changes", providerId);
        }
    }

    public List<String> providerIds() {
        List<String> ids = new ArrayList<>();
        for (ClaimProvider provider : providers) {
            ids.add(provider.providerId());
        }
        return ids;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    /** Helper so tests can exercise transform+publish without a server. */
    public void publishRawForTest(String providerId, List<ClaimProvider.RawArea> raw) {
        publish(providerId, transform(providerId, raw));
    }

    /** Slug lookup used by web layer for provider display names. */
    public static String describeProviders(List<ClaimProvider> providers) {
        List<String> names = new ArrayList<>();
        for (ClaimProvider provider : providers) {
            names.add(provider.displayName());
        }
        return String.join(", ", names);
    }

}
