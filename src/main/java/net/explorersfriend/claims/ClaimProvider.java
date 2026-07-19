package net.explorersfriend.claims;

import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Set;

/**
 * Adapter contract for one claim system. Implementations for optional mods must only
 * be class-loaded when the mod is present ({@code ClaimProviders.detect} guards this),
 * must never scan for absent mods, and must treat the provider's API as
 * server-thread-only: {@link #copyRawClaims} runs on the server thread and copies a
 * minimal snapshot quickly; all heavy processing happens elsewhere.
 */
public interface ClaimProvider {

    String providerId();

    String displayName();

    /** Cheap availability check after mod detection (API answering, manager loaded). */
    boolean isAvailable(MinecraftServer server);

    /**
     * Copies the raw claim state. Runs ON the server thread — keep it to plain data
     * copying, no rectangle math, no IO.
     */
    List<RawArea> copyRawClaims(MinecraftServer server);

    /**
     * Optionally hook the provider's change events; invoke {@code onChange} on any
     * change (any thread — the manager debounces). Default: polling only.
     */
    default void subscribe(MinecraftServer server, Runnable onChange) {
    }

    /**
     * One owner/team area in one dimension, raw from the provider.
     *
     * @param areaKey       stable identity within the provider (never shown to users)
     * @param dimensionId   dimension identifier ("minecraft:overworld")
     * @param chunks        claimed chunk positions ({@link ChunkRectMerger#pack}); may be
     *                      empty when {@code explicitRects} is supplied
     * @param explicitRects pre-made block-coordinate rectangles for providers with
     *                      region/polygon geometry (avoids exploding large areas into
     *                      thousands of chunk squares); null = derive from chunks
     * @param claimName     display name of the claim/area, or null
     * @param ownerName     display name of the owner, or null
     * @param teamName      team/group display name, or null
     * @param explicitColor provider-declared RGB color, or null
     * @param hidden        provider marks this area as not publicly visible
     */
    record RawArea(
            String areaKey,
            String dimensionId,
            Set<Long> chunks,
            List<MapClaim.ClaimRect> explicitRects,
            String claimName,
            String ownerName,
            String teamName,
            Integer explicitColor,
            boolean hidden) {

        public static RawArea ofChunks(String areaKey, String dimensionId, Set<Long> chunks,
                                       String claimName, String ownerName, String teamName,
                                       Integer explicitColor, boolean hidden) {
            return new RawArea(areaKey, dimensionId, chunks, null, claimName, ownerName,
                    teamName, explicitColor, hidden);
        }
    }
}
