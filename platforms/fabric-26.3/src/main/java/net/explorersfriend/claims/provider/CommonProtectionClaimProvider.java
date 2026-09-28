package net.explorersfriend.claims.provider;

import net.explorersfriend.claims.ChunkRectMerger;
import net.explorersfriend.claims.ClaimProvider;
import net.explorersfriend.claims.ProtectedChunkCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapter for the <a href="https://github.com/Patbox/common-protection-api">Common
 * Protection API</a> (compile-only) - the shared interface implemented by several
 * Fabric protection mods (e.g. GOML ReServed).
 *
 * <p>The API only answers point/area queries and cannot enumerate claims, so this
 * provider samples chunks as the server loads them (queued from the chunk-load
 * event, probed on the server tick with a fixed budget - never inside the chunk
 * callback, see the deadlock rule) and persists the protected set. The overlay
 * therefore shows protected areas where players have actually been; owners and
 * names are not available through this API. Documented in docs/CLAIM_PROVIDERS.md.</p>
 */
public final class CommonProtectionClaimProvider implements ClaimProvider {

    private static volatile CommonProtectionClaimProvider instance;

    private record Probe(ServerLevel world, int chunkX, int chunkZ) {
    }

    private static final int MAX_QUEUE = 10_000;
    private static final int PROBES_PER_TICK = 128;
    private static final int SAVE_INTERVAL_TICKS = 600;

    private final ConcurrentLinkedQueue<Probe> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final ProtectedChunkCache cache;
    private volatile Runnable onChange;
    private int saveCountdown = SAVE_INTERVAL_TICKS;

    public CommonProtectionClaimProvider(MinecraftServer server, Path cacheFile) {
        this.cache = new ProtectedChunkCache(cacheFile);
        cache.load();
        instance = this;
    }

    @Override
    public String providerId() {
        return "commonprotection";
    }

    @Override
    public String displayName() {
        return "Common Protection API";
    }

    @Override
    public boolean isAvailable() {
        try {
            return !Api.providerIds().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<RawArea> copyRawClaims() {
        return cache.toRawAreas();
    }

    @Override
    public void subscribe(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Called from the chunk-load path: only queues - the probe runs on the tick. */
    public static void chunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
        CommonProtectionClaimProvider self = instance;
        if (self == null || self.queued.get() >= MAX_QUEUE) {
            return;
        }
        self.queue.add(new Probe(world, chunkX, chunkZ));
        self.queued.incrementAndGet();
    }

    /** Called once per server tick (server thread). */
    public static void tickCurrent() {
        CommonProtectionClaimProvider self = instance;
        if (self != null) {
            self.tick();
        }
    }

    private void tick() {
        int budget = PROBES_PER_TICK;
        boolean changed = false;
        Probe probe;
        while (budget-- > 0 && (probe = queue.poll()) != null) {
            queued.decrementAndGet();
            boolean isProtected;
            try {
                isProtected = Api.isAreaProtected(probe.world(), probe.chunkX(), probe.chunkZ());
            } catch (Throwable t) {
                continue;
            }
            String dimensionId = probe.world().dimension().identifier().toString();
            changed |= cache.update(dimensionId,
                    ChunkRectMerger.pack(probe.chunkX(), probe.chunkZ()), isProtected);
        }
        Runnable listener = onChange;
        if (changed && listener != null) {
            listener.run();
        }
        if (--saveCountdown <= 0) {
            saveCountdown = SAVE_INTERVAL_TICKS;
            cache.saveIfDirty();
        }
    }

    /** The only place that touches Common Protection API classes. */
    private static final class Api {
        static java.util.Collection<?> providerIds() {
            return eu.pb4.common.protection.api.CommonProtection.getProviderIds();
        }

        static boolean isAreaProtected(ServerLevel world, int chunkX, int chunkZ) {
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            AABB box = new AABB(minX, -2048, minZ, minX + 16, 2048, minZ + 16);
            return eu.pb4.common.protection.api.CommonProtection.isAreaProtected(world, box);
        }
    }
}
