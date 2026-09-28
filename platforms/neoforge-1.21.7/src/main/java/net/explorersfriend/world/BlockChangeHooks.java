package net.explorersfriend.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Static bridge between the {@code LevelChunkMixin} and the per-server map service.
 *
 * <p>Thread-safety: {@link #listener} is a volatile reference written only from the
 * server lifecycle (install on start, clear on shutdown). {@code onBlockChanged} is
 * invoked on whichever thread mutates a chunk (normally the server thread) and must
 * therefore stay allocation-light and lock-free; the installed listener forwards to
 * a concurrent dirty-set.</p>
 */
public final class BlockChangeHooks {

    /** Receives every real server-side block change. Implementations must be cheap and thread-safe. */
    @FunctionalInterface
    public interface Listener {
        void onBlockChanged(Level world, BlockPos pos, BlockState newState);
    }

    private static volatile Listener listener;

    private BlockChangeHooks() {
    }

    public static void install(Listener newListener) {
        listener = newListener;
    }

    public static void uninstall() {
        listener = null;
    }

    public static void onBlockChanged(Level world, BlockPos pos, BlockState newState) {
        Listener current = listener;
        if (current != null) {
            current.onBlockChanged(world, pos, newState);
        }
    }
}
