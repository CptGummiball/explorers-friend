package net.explorersfriend.spigot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.explorersfriend.config.ConfigIO;
import net.explorersfriend.config.MapConfig;
import net.explorersfriend.platform.PlatformInfo;
import net.explorersfriend.render.TileStore;
import net.explorersfriend.util.Log;
import net.explorersfriend.web.MapHttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Spigot/Paper backend (milestone 1: platform bootstrap, web server, worlds,
 * status; rendering/claims/markers land in the follow-up milestones documented in
 * MULTIPLATFORM.md). Uses only the version-stable Bukkit API - no NMS, no mixins;
 * the same jar targets every supported Minecraft version (api-version 1.21).
 */
public final class ExplorersFriendPlugin extends JavaPlugin {

    private record BukkitDimension(String id, String slug, int spawnX, int spawnZ) {
    }

    private MapConfig config;
    private Path dataDir;
    private TileStore tileStore;
    private MapHttpServer webServer;
    private volatile Map<String, BukkitDimension> dimensions = Map.of();
    private volatile byte[] worldsJson = "{\"worlds\":[]}".getBytes(StandardCharsets.UTF_8);
    private final long startNanos = System.nanoTime();

    @Override
    public void onEnable() {
        String mcVersion = Bukkit.getBukkitVersion().split("-", 2)[0];
        String serverName = Bukkit.getServer().getName();   // "CraftBukkit" on Spigot, "Paper" on Paper
        String platformId = serverName.toLowerCase(Locale.ROOT).contains("paper") ? "paper" : "spigot";
        PlatformInfo.set(new PlatformInfo(platformId, mcVersion, "spigot", getDescription().getVersion()));

        Path configDir = getDataFolder().toPath();
        config = ConfigIO.loadOrCreate(configDir.resolve("config.jsonc"));
        dataDir = Bukkit.getWorldContainer().toPath().resolve(config.storage().dataDir());
        tileStore = new TileStore(dataDir.resolve("tiles"));

        refreshDimensions();
        detectIntegrations();
        startWebServer();
        Log.LOGGER.info("[ExplorersFriend/Init] Spigot backend milestone 1 ready - {} world(s), web {}",
                dimensions.size(), webServer == null ? "disabled" : "on port " + config.web().port());
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.close();
            webServer = null;
        }
        Log.LOGGER.info("[ExplorersFriend/Init] Shutdown complete");
    }

    // --- worlds -------------------------------------------------------------

    private static String dimensionId(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:" + world.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
        };
    }

    private void refreshDimensions() {
        Map<String, BukkitDimension> found = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            String id = dimensionId(world);
            String slug = TileStore.dimensionSlug(id);
            found.put(slug, new BukkitDimension(id, slug,
                    world.getSpawnLocation().getBlockX(), world.getSpawnLocation().getBlockZ()));
        }
        dimensions = Map.copyOf(found);
        JsonObject root = new JsonObject();
        root.addProperty("title", config.web().title());
        root.addProperty("playersIntervalSeconds", config.players().updateIntervalSeconds());
        JsonArray worlds = new JsonArray();
        for (BukkitDimension dim : found.values()) {
            JsonObject world = new JsonObject();
            world.addProperty("id", dim.id());
            world.addProperty("slug", dim.slug());
            world.addProperty("zoomLevels", config.render().zoomLevels());
            world.addProperty("spawnX", dim.spawnX());
            world.addProperty("spawnZ", dim.spawnZ());
            worlds.add(world);
        }
        root.add("worlds", worlds);
        worldsJson = root.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- integrations -------------------------------------------------------

    private void detectIntegrations() {
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) {
            Log.LOGGER.info("[ExplorersFriend/Claims] GriefPrevention detected "
                    + "(adapter lands in milestone 3)");
        } else {
            Log.LOGGER.info("[ExplorersFriend/Claims] GriefPrevention: not installed");
        }
        Log.LOGGER.info("[ExplorersFriend/Claims] FTB Chunks / Open Parties and Claims: "
                + "not applicable on this platform (mod-loader mods)");
    }

    // --- web ----------------------------------------------------------------

    private void startWebServer() {
        if (!config.web().enabled()) {
            return;
        }
        try {
            webServer = new MapHttpServer(config.web().bind(), config.web().port(),
                    config.web().threads(), config.web().connectionLimit(),
                    config.web().idleTimeoutSeconds(), config.web().gzip(),
                    new WebData(), getDescription().getVersion());
        } catch (IOException e) {
            Log.LOGGER.error("[ExplorersFriend/Web] Could not start the web server on {}:{} ({})",
                    config.web().bind(), config.web().port(), e.toString());
        }
    }

    private final class WebData implements MapHttpServer.DataSource {

        @Override
        public byte[] playersJson() {
            return "{\"players\":[]}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] statusJson() {
            JsonObject root = new JsonObject();
            root.addProperty("name", "The Explorer's Friend");
            root.addProperty("platform", PlatformInfo.get().platformId());
            root.addProperty("minecraftVersion", PlatformInfo.get().minecraftVersion());
            root.addProperty("ready", true);
            root.addProperty("readOnly", false);
            root.addProperty("uptimeSeconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] worldsJson() {
            return worldsJson;
        }

        @Override
        public Path tileFile(String dimensionSlug, int zoom, int tileX, int tileZ) {
            if (tileStore == null || !dimensions.containsKey(dimensionSlug)
                    || zoom < 0 || zoom > config.render().zoomLevels()) {
                return null;
            }
            return tileStore.tilePath(dimensionSlug, zoom, tileX, tileZ);
        }
    }

    // --- commands -----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("The Explorer's Friend " + getDescription().getVersion()
                    + " on " + PlatformInfo.get().platformId());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (!sender.hasPermission("explorersfriend.command.status")) {
                    sender.sendMessage("Missing permission explorersfriend.command.status");
                    return true;
                }
                sender.sendMessage("Platform: " + PlatformInfo.get().platformId()
                        + " | Minecraft " + PlatformInfo.get().minecraftVersion()
                        + " | worlds: " + dimensions.size()
                        + " | web: " + (webServer == null ? "off" : config.web().bind() + ":" + config.web().port()));
                return true;
            }
            case "web" -> {
                sender.sendMessage(webServer == null ? "Web server is not running."
                        : "Web server on " + config.web().bind() + ":" + config.web().port());
                return true;
            }
            default -> {
                sender.sendMessage("Milestone 1 supports: /efmap status, /efmap web "
                        + "(render/markers/claims follow in the next milestones)");
                return true;
            }
        }
    }
}
