package net.explorersfriend.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents log floods from per-file or per-chunk events: {@link #shouldLog(String, long)}
 * returns true at most once per interval per key. Thread-safe, allocation-light
 * (one map entry per distinct key).
 */
public final class RateLimitedLog {

    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    /**
     * @return true if the caller should emit the log line now; false if the same key
     *         was already logged less than {@code intervalMs} ago.
     */
    public boolean shouldLog(String key, long intervalMs) {
        long now = System.nanoTime();
        long intervalNanos = intervalMs * 1_000_000L;
        Long updated = lastLogged.compute(key, (k, prev) ->
                prev == null || now - prev >= intervalNanos ? now : prev);
        return updated != null && updated == now;
    }

    public void reset() {
        lastLogged.clear();
    }
}
