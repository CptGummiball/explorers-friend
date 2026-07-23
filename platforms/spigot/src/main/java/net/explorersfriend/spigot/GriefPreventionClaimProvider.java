package net.explorersfriend.spigot;

import net.explorersfriend.claims.ClaimProvider;
import net.explorersfriend.claims.MapClaim;
import net.explorersfriend.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Adapter for <a href="https://modrinth.com/plugin/griefprevention">GriefPrevention</a>
 * via its public API ({@code GriefPrevention.instance.dataStore}, compile-only,
 * declared as softdepend in plugin.yml). Claims are exported as explicit block
 * rectangles (GP claims are block-, not chunk-based); subclaims become their own
 * areas under the same owner. GP exposes no claim names or colors - areas use the
 * owner name plus the deterministic color chain of the shared claim pipeline.
 * Admin claims are labeled "Admin". Change events (create/delete/modify/resize/
 * extend) trigger debounced refreshes through the shared ClaimManager.
 *
 * <p>Only the isolated {@code Api}/{@code Events} classes touch GP types, so
 * nothing is class-loaded unless the plugin is actually installed.</p>
 */
final class GriefPreventionClaimProvider implements ClaimProvider {

    private final JavaPlugin plugin;
    private final Function<World, String> dimensionId;
    private volatile Runnable onChange;

    GriefPreventionClaimProvider(JavaPlugin plugin, Function<World, String> dimensionId) {
        this.plugin = plugin;
        this.dimensionId = dimensionId;
    }

    @Override
    public String providerId() {
        return "griefprevention";
    }

    @Override
    public String displayName() {
        return "GriefPrevention";
    }

    @Override
    public boolean isAvailable() {
        try {
            return Api.available();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<RawArea> copyRawClaims() {
        try {
            return Api.collect(dimensionId);
        } catch (Throwable t) {
            Log.LOGGER.warn("[ExplorersFriend/Claims] GriefPrevention API error: {}", t.toString());
            return List.of();
        }
    }

    @Override
    public void subscribe(Runnable onChange) {
        this.onChange = onChange;
        try {
            Bukkit.getPluginManager().registerEvents(new Events(), plugin);
        } catch (Throwable t) {
            Log.LOGGER.warn("[ExplorersFriend/Claims] GriefPrevention events unavailable "
                    + "({}); falling back to periodic refresh only", t.toString());
        }
    }

    private void fire() {
        Runnable listener = onChange;
        if (listener != null) {
            listener.run();
        }
    }

    /** The only class that references GriefPrevention API types directly. */
    private static final class Api {

        static boolean available() {
            return me.ryanhamshire.GriefPrevention.GriefPrevention.instance != null
                    && me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore != null;
        }

        static List<RawArea> collect(Function<World, String> dimensionId) {
            List<RawArea> out = new ArrayList<>();
            for (me.ryanhamshire.GriefPrevention.Claim claim
                    : me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaims()) {
                addClaim(out, claim, dimensionId);
                if (claim.children != null) {
                    for (me.ryanhamshire.GriefPrevention.Claim child : claim.children) {
                        addClaim(out, child, dimensionId);
                    }
                }
            }
            return out;
        }

        private static void addClaim(List<RawArea> out,
                                     me.ryanhamshire.GriefPrevention.Claim claim,
                                     Function<World, String> dimensionId) {
            org.bukkit.Location lesser = claim.getLesserBoundaryCorner();
            org.bukkit.Location greater = claim.getGreaterBoundaryCorner();
            if (lesser == null || greater == null || lesser.getWorld() == null) {
                return;
            }
            String owner = claim.isAdminClaim() ? "Admin" : claim.getOwnerName();
            out.add(new RawArea(
                    "gp|" + claim.getID(),
                    dimensionId.apply(lesser.getWorld()),
                    null,
                    List.of(new MapClaim.ClaimRect(
                            lesser.getBlockX(), lesser.getBlockZ(),
                            greater.getBlockX(), greater.getBlockZ())),
                    null,
                    owner,
                    null,
                    null,
                    false));
        }
    }

    /** Bukkit listener over GP's claim change events (loaded only when GP exists). */
    private final class Events implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onCreated(me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent event) {
            fire();
        }

        @EventHandler(ignoreCancelled = true)
        public void onDeleted(me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent event) {
            fire();
        }

        @EventHandler(ignoreCancelled = true)
        public void onModified(me.ryanhamshire.GriefPrevention.events.ClaimModifiedEvent event) {
            fire();
        }
    }
}
