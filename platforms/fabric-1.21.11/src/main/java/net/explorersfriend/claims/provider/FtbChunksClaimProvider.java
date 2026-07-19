package net.explorersfriend.claims.provider;

import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.explorersfriend.claims.ChunkRectMerger;
import net.explorersfriend.claims.ClaimProvider;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for <a href="https://www.feed-the-beast.com/">FTB Chunks</a> via its official
 * {@code dev.ftb.mods.ftbchunks.api} API (compile-only dependency).
 *
 * <p>This class must only be loaded when the "ftbchunks" mod is present —
 * {@code ClaimProviders.detect} guarantees that. Claims are grouped per (team,
 * dimension); teams flagged with {@code shouldHideClaims()} are respected and never
 * leave the server. Change events (Architectury-based) trigger debounced refreshes;
 * the periodic sync remains authoritative.</p>
 */
public final class FtbChunksClaimProvider implements ClaimProvider {

    private final MinecraftServer server;

    public FtbChunksClaimProvider(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String providerId() {
        return "ftbchunks";
    }

    @Override
    public String displayName() {
        return "FTB Chunks";
    }

    @Override
    public boolean isAvailable() {
        try {
            return FTBChunksAPI.api().isManagerLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<RawArea> copyRawClaims() {
        record TeamArea(Set<Long> chunks, ChunkTeamData data) {
        }
        Map<String, TeamArea> areas = new HashMap<>();
        for (ClaimedChunk chunk : FTBChunksAPI.api().getManager().getAllClaimedChunks()) {
            ChunkDimPos pos = chunk.getPos();
            ChunkTeamData data = chunk.getTeamData();
            Team team = data.getTeam();
            if (team == null) {
                continue;
            }
            String key = team.getTeamId() + "|" + pos.dimension().getValue();
            areas.computeIfAbsent(key, k -> new TeamArea(new HashSet<>(), data))
                    .chunks().add(ChunkRectMerger.pack(pos.x(), pos.z()));
        }
        List<RawArea> out = new ArrayList<>(areas.size());
        for (Map.Entry<String, TeamArea> entry : areas.entrySet()) {
            TeamArea area = entry.getValue();
            Team team = area.data().getTeam();
            String dimensionId = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            String displayName = team.getProperty(TeamProperties.DISPLAY_NAME);
            if (displayName == null || displayName.isBlank()) {
                displayName = team.getShortName();
            }
            Integer color = null;
            Color4I teamColor = team.getProperty(TeamProperties.COLOR);
            if (teamColor != null) {
                color = (teamColor.redi() << 16) | (teamColor.greeni() << 8) | teamColor.bluei();
            }
            boolean isParty = team.isPartyTeam();
            out.add(RawArea.ofChunks(
                    entry.getKey(),
                    dimensionId,
                    area.chunks(),
                    null,                              // FTB claims carry no per-claim name
                    isParty ? ownerName(server, team.getOwner(), displayName) : displayName,
                    isParty ? displayName : null,
                    color,
                    area.data().shouldHideClaims()));
        }
        return out;
    }

    private static String ownerName(MinecraftServer server, UUID owner, String fallback) {
        if (owner == null) {
            return fallback;
        }
        return server.getApiServices().nameToIdCache().getByUuid(owner)
                .map(net.minecraft.server.PlayerConfigEntry::name)
                .orElse(fallback);
    }

    @Override
    public void subscribe(Runnable onChange) {
        // Architectury events are static registries; register once on the server thread.
        server.execute(() -> {
            ClaimedChunkEvent.AFTER_CLAIM.register((source, chunk) -> onChange.run());
            ClaimedChunkEvent.AFTER_UNCLAIM.register((source, chunk) -> onChange.run());
        });
    }
}
