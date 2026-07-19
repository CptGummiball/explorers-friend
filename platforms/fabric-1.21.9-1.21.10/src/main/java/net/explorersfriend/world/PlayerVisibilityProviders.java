package net.explorersfriend.world;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Platform-side extension point for vanish/permission mods (moved out of the common
 * API in 0.3.0 because it references a Minecraft type and those names differ between
 * mapping eras). Register during your mod init; called on the server thread during
 * the periodic sample — keep it cheap. Any veto hides the player; a throwing provider
 * hides defensively.
 */
public final class PlayerVisibilityProviders {

    @FunctionalInterface
    public interface PlayerVisibilityProvider {
        boolean shouldDisplay(ServerPlayerEntity player);
    }

    private static final CopyOnWriteArrayList<PlayerVisibilityProvider> PROVIDERS =
            new CopyOnWriteArrayList<>();

    private PlayerVisibilityProviders() {
    }

    public static void register(PlayerVisibilityProvider provider) {
        PROVIDERS.add(provider);
    }

    public static List<PlayerVisibilityProvider> all() {
        return PROVIDERS;
    }
}
