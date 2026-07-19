package net.explorersfriend.util;


import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon thread factory with stable names ({@code EF-Render-1}, {@code EF-Scan-2}, …)
 * and an uncaught-exception handler that logs instead of dying silently. Daemon
 * threads guarantee the JVM can always exit even if a worker hangs.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) ->
                Log.LOGGER.error("[ExplorersFriend/Worker] Thread {} died unexpectedly", t.getName(), e));
        return thread;
    }
}
