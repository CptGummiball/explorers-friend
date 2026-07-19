package net.explorersfriend.claims.provider;

import net.explorersfriend.claims.ChunkRectMerger;
import net.explorersfriend.claims.ClaimProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for <a href="https://modrinth.com/mod/open-parties-and-claims">Open Parties
 * and Claims</a> via its official {@code xaero.pac.*.api} API (compile-only dependency,
 * LGPL-3.0 — nothing bundled).
 *
 * <p>Only loaded when "openpartiesandclaims" is present. OPAC exposes per-player claim
 * names and colors including sub-claims; the tracker API delivers change events which
 * we debounce into refreshes.</p>
 */
public final class OpacClaimProvider implements ClaimProvider {

    private final MinecraftServer server;

    public OpacClaimProvider(MinecraftServer server) {
        this.server = server;
    }

    private static final UUID SERVER_CLAIMS_UUID = new UUID(0, 0);

    @Override
    public String providerId() {
        return "openpartiesandclaims";
    }

    @Override
    public String displayName() {
        return "Open Parties and Claims";
    }

    @Override
    public boolean isAvailable() {
        try {
            return OpenPACServerAPI.get(server) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<RawArea> copyRawClaims() {
        IServerClaimsManagerAPI manager = OpenPACServerAPI.get(server).getServerClaimsManager();
        List<RawArea> out = new ArrayList<>();
        manager.getPlayerInfoStream().forEach(info -> {
            UUID playerId = info.getPlayerId();
            String username = info.getPlayerUsername();
            boolean serverClaims = SERVER_CLAIMS_UUID.equals(playerId);
            String owner = serverClaims ? "Server" : username;
            String mainName = blankToNull(info.getClaimsName());
            Integer mainColor = zeroToNull(info.getClaimsColor());

            // group positions per dimension by sub-config (sub-claims have own name/color)
            info.getStream().forEach(dimensionEntry -> {
                Identifier dimension = dimensionEntry.getKey();
                Map<Integer, Set<Long>> chunksBySub = new HashMap<>();
                dimensionEntry.getValue().getStream().forEach(posList -> {
                    IPlayerChunkClaimAPI state = posList.getClaimState();
                    Set<Long> chunks = chunksBySub.computeIfAbsent(state.getSubConfigIndex(),
                            k -> new HashSet<>());
                    posList.getStream().forEach(chunkPos ->
                            chunks.add(ChunkRectMerger.pack(chunkPos.x(), chunkPos.z())));
                });
                chunksBySub.forEach((subIndex, chunks) -> {
                    if (chunks.isEmpty()) {
                        return;
                    }
                    String claimName = blankToNull(info.getClaimsName(subIndex));
                    Integer color = zeroToNull(info.getClaimsColor(subIndex));
                    out.add(RawArea.ofChunks(
                            playerId + "|" + dimension + "|" + subIndex,
                            dimension.toString(),
                            chunks,
                            claimName != null ? claimName : mainName,
                            owner,
                            null,
                            color != null ? color : mainColor,
                            false));
                });
            });
        });
        return out;
    }

    @Override
    public void subscribe(Runnable onChange) {
        server.execute(() -> OpenPACServerAPI.get(server).getServerClaimsManager().getTracker()
                .register(new IClaimsManagerListenerAPI() {
                    @Override
                    public void onWholeRegionChange(Identifier dimension, int regionX, int regionZ) {
                        onChange.run();
                    }

                    @Override
                    public void onChunkChange(Identifier dimension, int chunkX, int chunkZ,
                                              IPlayerChunkClaimAPI claim) {
                        onChange.run();
                    }

                    @Override
                    public void onDimensionChange(Identifier dimension) {
                        onChange.run();
                    }
                }));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer zeroToNull(Integer value) {
        return value == null || (value & 0xFFFFFF) == 0 ? null : value;
    }
}
