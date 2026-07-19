package net.explorersfriend.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.util.Jsonc;
import net.explorersfriend.util.MoreFiles;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads and writes {@code config.jsonc}. Missing file: a fully commented default
 * template is written. Broken file: quarantined, defaults used, server keeps running.
 * Individual invalid values: WARN + safe default for that value only.
 */
public final class ConfigIO {

    private static final Logger LOGGER = ExplorersFriend.LOGGER;

    private ConfigIO() {
    }

    public static MapConfig loadOrCreate(Path configFile) {
        MapConfig defaults = MapConfig.defaults();
        if (!Files.exists(configFile)) {
            try {
                MoreFiles.writeAtomicUtf8(configFile, defaultTemplate());
                LOGGER.info("[ExplorersFriend/Config] Wrote default configuration to {}", configFile);
            } catch (IOException e) {
                LOGGER.error("[ExplorersFriend/Config] Could not write default config to {}; using built-in defaults",
                        configFile, e);
            }
            return defaults;
        }
        String raw;
        try {
            raw = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[ExplorersFriend/Config] Could not read {}; using defaults", configFile, e);
            return defaults;
        }
        JsonObject root;
        try {
            JsonElement parsed = Jsonc.parse(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("top level is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            Path backup = MoreFiles.quarantine(configFile);
            LOGGER.error("[ExplorersFriend/Config] {} is not valid JSON ({}). File moved to {}, defaults written.",
                    configFile, e.getMessage(), backup);
            try {
                MoreFiles.writeAtomicUtf8(configFile, defaultTemplate());
            } catch (IOException io) {
                LOGGER.error("[ExplorersFriend/Config] Could not rewrite default config", io);
            }
            return defaults;
        }
        List<String> warnings = new ArrayList<>();
        MapConfig config = read(root, defaults, warnings);
        for (String warning : warnings) {
            LOGGER.warn("[ExplorersFriend/Config] {}", warning);
        }
        return config;
    }

    static MapConfig read(JsonObject root, MapConfig def, List<String> warnings) {
        JsonObject web = obj(root, "web");
        JsonObject render = obj(root, "render");
        JsonObject scan = obj(root, "scan");
        JsonObject storage = obj(root, "storage");
        JsonObject worlds = obj(root, "worlds");
        JsonObject players = obj(root, "players");
        JsonObject logging = obj(root, "logging");
        JsonObject blocks = obj(root, "blocks");
        JsonObject claims = obj(root, "claims");
        JsonObject markers = obj(root, "markers");
        JsonObject performance = obj(root, "performance");

        return new MapConfig(
                new MapConfig.Web(
                        getBool(web, "web.enabled", def.web().enabled(), warnings),
                        getString(web, "bind", "web.bind", def.web().bind(), warnings),
                        getInt(web, "port", "web.port", def.web().port(), 1, 65535, warnings),
                        getString(web, "public-base-url", "web.public-base-url", def.web().publicBaseUrl(), warnings),
                        getString(web, "title", "web.title", def.web().title(), warnings),
                        getInt(web, "threads", "web.threads", def.web().threads(), 1, 16, warnings),
                        getBool(web, "web.gzip", def.web().gzip(), warnings, "gzip"),
                        getInt(web, "connection-limit", "web.connection-limit", def.web().connectionLimit(), 4, 4096, warnings),
                        getInt(web, "idle-timeout-seconds", "web.idle-timeout-seconds", def.web().idleTimeoutSeconds(), 5, 600, warnings),
                        getBool(web, "web.metrics-enabled", def.web().metricsEnabled(), warnings, "metrics-enabled")),
                new MapConfig.Render(
                        getInt(render, "workers", "render.workers", def.render().workers(), 1, 32, warnings),
                        getInt(render, "tick-budget-micros", "render.tick-budget-micros", def.render().tickBudgetMicros(), 100, 20_000, warnings),
                        getInt(render, "max-snapshots-per-tick", "render.max-snapshots-per-tick", def.render().maxSnapshotsPerTick(), 1, 64, warnings),
                        getInt(render, "max-queued-tiles", "render.max-queued-tiles", def.render().maxQueuedTiles(), 64, 1_000_000, warnings),
                        getInt(render, "zoom-levels", "render.zoom-levels", def.render().zoomLevels(), 1, 8, warnings),
                        getBool(render, "render.height-shading", def.render().heightShading(), warnings, "height-shading"),
                        getBool(render, "render.water-depth-shading", def.render().waterDepthShading(), warnings, "water-depth-shading"),
                        getInt(render, "update-debounce-seconds", "render.update-debounce-seconds", def.render().updateDebounceSeconds(), 1, 600, warnings),
                        getInt(render, "update-max-delay-seconds", "render.update-max-delay-seconds", def.render().updateMaxDelaySeconds(), 5, 3600, warnings),
                        getBool(render, "render.full-render-on-first-start", def.render().fullRenderOnFirstStart(), warnings, "full-render-on-first-start"),
                        getBool(render, "render.render-new-chunks", def.render().renderNewChunks(), warnings, "render-new-chunks")),
                new MapConfig.Scan(
                        getInt(scan, "threads", "scan.threads", def.scan().threads(), 1, 16, warnings),
                        getBool(scan, "scan.download-vanilla-assets", def.scan().downloadVanillaAssets(), warnings, "download-vanilla-assets"),
                        getEnum(scan, "animated-textures", "scan.animated-textures", def.scan().animatedTextures(),
                                List.of("first_frame", "average"), warnings),
                        getStringList(scan, "exclude-mods", "scan.exclude-mods", def.scan().excludeMods(), warnings),
                        getStringList(scan, "exclude-namespaces", "scan.exclude-namespaces", def.scan().excludeNamespaces(), warnings),
                        getInt(scan, "max-texture-edge", "scan.max-texture-edge", def.scan().maxTextureEdge(), 16, 16_384, warnings),
                        getInt(scan, "max-zip-entries", "scan.max-zip-entries", def.scan().maxZipEntries(), 100, 5_000_000, warnings),
                        getLong(scan, "max-entry-bytes", "scan.max-entry-bytes", def.scan().maxEntryBytes(), 1024, 1L << 31, warnings)),
                new MapConfig.Storage(
                        getString(storage, "data-dir", "storage.data-dir", def.storage().dataDir(), warnings),
                        getLong(storage, "max-tile-cache-mb", "storage.max-tile-cache-mb", def.storage().maxTileCacheMb(), 0, 1L << 22, warnings),
                        getBool(storage, "storage.prune-caches-on-start", def.storage().pruneCachesOnStart(), warnings, "prune-caches-on-start")),
                new MapConfig.Worlds(
                        getStringList(worlds, "enabled", "worlds.enabled", def.worlds().enabled(), warnings),
                        getStringList(worlds, "disabled", "worlds.disabled", def.worlds().disabled(), warnings),
                        getInt(worlds, "max-render-radius-blocks", "worlds.max-render-radius-blocks", def.worlds().maxRenderRadiusBlocks(), 0, 30_000_000, warnings)),
                new MapConfig.Players(
                        getBool(players, "players.show", def.players().show(), warnings, "show"),
                        getBool(players, "players.default-visible-in-ui", def.players().defaultVisibleInUi(), warnings, "default-visible-in-ui"),
                        getInt(players, "update-interval-seconds", "players.update-interval-seconds", def.players().updateIntervalSeconds(), 1, 300, warnings),
                        getBool(players, "players.hide-invisible", def.players().hideInvisible(), warnings, "hide-invisible"),
                        getBool(players, "players.hide-spectators", def.players().hideSpectators(), warnings, "hide-spectators"),
                        getInt(players, "position-rounding", "players.position-rounding", def.players().positionRounding(), 1, 256, warnings),
                        getInt(players, "position-delay-seconds", "players.position-delay-seconds", def.players().positionDelaySeconds(), 0, 600, warnings),
                        getBool(players, "players.show-names", def.players().showNames(), warnings, "show-names"),
                        getBool(players, "players.show-coordinates", def.players().showCoordinates(), warnings, "show-coordinates"),
                        getBool(players, "players.anonymize-names", def.players().anonymizeNames(), warnings, "anonymize-names"),
                        getBool(players, "players.allow-external-skin-lookup", def.players().allowExternalSkinLookup(), warnings, "allow-external-skin-lookup"),
                        getInt(players, "skin-cache-hours", "players.skin-cache-hours", def.players().skinCacheHours(), 1, 8760, warnings),
                        getStringList(players, "hidden-players", "players.hidden-players", def.players().hiddenPlayers(), warnings),
                        getStringList(players, "disabled-worlds", "players.disabled-worlds", def.players().disabledWorlds(), warnings)),
                new MapConfig.Logging(
                        getInt(logging, "progress-interval-seconds", "logging.progress-interval-seconds", def.logging().progressIntervalSeconds(), 1, 300, warnings),
                        getBool(logging, "logging.debug", def.logging().debug(), warnings, "debug")),
                new MapConfig.Blocks(
                        getColor(blocks, "unknown-block-color", "blocks.unknown-block-color", def.blocks().unknownBlockColor(), warnings),
                        getStringList(blocks, "exclude-blocks", "blocks.exclude-blocks", def.blocks().excludeBlocks(), warnings)),
                new MapConfig.Claims(
                        getBool(claims, "claims.enabled", def.claims().enabled(), warnings),
                        getBool(claims, "claims.default-visible-in-ui", def.claims().defaultVisibleInUi(), warnings, "default-visible-in-ui"),
                        getInt(claims, "refresh-interval-seconds", "claims.refresh-interval-seconds", def.claims().refreshIntervalSeconds(), 10, 3600, warnings),
                        getDouble(claims, "fill-opacity", "claims.fill-opacity", def.claims().fillOpacity(), 0.05, 0.9, warnings),
                        getDouble(claims, "border-opacity", "claims.border-opacity", def.claims().borderOpacity(), 0.3, 1.0, warnings),
                        getInt(claims, "border-width", "claims.border-width", def.claims().borderWidth(), 1, 8, warnings),
                        getBool(claims, "claims.show-owner", def.claims().showOwner(), warnings, "show-owner"),
                        getBool(claims, "claims.show-name", def.claims().showName(), warnings, "show-name"),
                        getBool(claims, "claims.show-team", def.claims().showTeam(), warnings, "show-team"),
                        getStringList(claims, "enabled-providers", "claims.enabled-providers", def.claims().enabledProviders(), warnings),
                        getStringList(claims, "disabled-worlds", "claims.disabled-worlds", def.claims().disabledWorlds(), warnings),
                        getInt(claims, "max-claims-per-response", "claims.max-claims-per-response", def.claims().maxClaimsPerResponse(), 100, 100_000, warnings),
                        getColor(claims, "default-color", "claims.default-color", def.claims().defaultColor(), warnings) & 0xFFFFFF),
                new MapConfig.Markers(
                        getBool(markers, "markers.enabled", def.markers().enabled(), warnings),
                        getBool(markers, "markers.default-visible-in-ui", def.markers().defaultVisibleInUi(), warnings, "default-visible-in-ui"),
                        getBool(markers, "markers.banners-default-visible-in-ui", def.markers().bannersDefaultVisibleInUi(), warnings, "banners-default-visible-in-ui"),
                        getInt(markers, "max-per-player", "markers.max-per-player", def.markers().maxPerPlayer(), 1, 10_000, warnings),
                        getInt(markers, "max-total", "markers.max-total", def.markers().maxTotal(), 10, 1_000_000, warnings),
                        getBool(markers, "markers.allow-player-creation", def.markers().allowPlayerCreation(), warnings, "allow-player-creation"),
                        getBool(markers, "markers.allow-banner-markers", def.markers().allowBannerMarkers(), warnings, "allow-banner-markers"),
                        getBool(markers, "markers.remove-marker-with-banner", def.markers().removeMarkerWithBanner(), warnings, "remove-marker-with-banner"),
                        getBool(markers, "markers.show-creator", def.markers().showCreator(), warnings, "show-creator"),
                        getBool(markers, "markers.show-coordinates", def.markers().showCoordinates(), warnings, "show-coordinates"),
                        getStringList(markers, "disabled-worlds", "markers.disabled-worlds", def.markers().disabledWorlds(), warnings),
                        getInt(markers, "save-interval-seconds", "markers.save-interval-seconds", def.markers().saveIntervalSeconds(), 5, 3600, warnings)),
                new MapConfig.Performance(
                        getBool(performance, "performance.auto-throttle", def.performance().autoThrottle(), warnings, "auto-throttle"),
                        getInt(performance, "mspt-pause-threshold", "performance.mspt-pause-threshold", def.performance().msptPauseThreshold(), 20, 200, warnings),
                        getInt(performance, "mspt-resume-threshold", "performance.mspt-resume-threshold", def.performance().msptResumeThreshold(), 10, 190, warnings)));
    }

    private static double getDouble(JsonObject obj, String key, String label, double def, double min, double max, List<String> warnings) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        try {
            double value = el.getAsDouble();
            if (value < min || value > max) {
                warnings.add(label + " = " + value + " is outside [" + min + ", " + max + "]; using " + def);
                return def;
            }
            return value;
        } catch (Exception e) {
            warnings.add(label + " must be a number; using " + def);
            return def;
        }
    }

    // --- primitive readers -------------------------------------------------

    private static JsonObject obj(JsonObject root, String key) {
        JsonElement el = root.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static boolean getBool(JsonObject obj, String label, boolean def, List<String> warnings) {
        return getBool(obj, label, def, warnings, "enabled");
    }

    private static boolean getBool(JsonObject obj, String label, boolean def, List<String> warnings, String key) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        warnings.add(label + " must be true or false; using " + def);
        return def;
    }

    private static int getInt(JsonObject obj, String key, String label, int def, int min, int max, List<String> warnings) {
        return (int) getLong(obj, key, label, def, min, max, warnings);
    }

    private static long getLong(JsonObject obj, String key, String label, long def, long min, long max, List<String> warnings) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        try {
            long value = el.getAsLong();
            if (value < min || value > max) {
                warnings.add(label + " = " + value + " is outside [" + min + ", " + max + "]; using " + def);
                return def;
            }
            return value;
        } catch (Exception e) {
            warnings.add(label + " must be a number; using " + def);
            return def;
        }
    }

    private static String getString(JsonObject obj, String key, String label, String def, List<String> warnings) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return el.getAsString();
        }
        warnings.add(label + " must be a string; using \"" + def + "\"");
        return def;
    }

    private static String getEnum(JsonObject obj, String key, String label, String def, List<String> allowed, List<String> warnings) {
        String value = getString(obj, key, label, def, warnings).toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            warnings.add(label + " = \"" + value + "\" is not one of " + allowed + "; using \"" + def + "\"");
            return def;
        }
        return value;
    }

    private static List<String> getStringList(JsonObject obj, String key, String label, List<String> def, List<String> warnings) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        if (!el.isJsonArray()) {
            warnings.add(label + " must be a list of strings; using default");
            return def;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                out.add(item.getAsString());
            } else {
                warnings.add(label + " contains a non-string entry; entry skipped");
            }
        }
        return List.copyOf(out);
    }

    private static int getColor(JsonObject obj, String key, String label, int def, List<String> warnings) {
        JsonElement el = obj.get(key);
        if (el == null) {
            return def;
        }
        String text = el.isJsonPrimitive() ? el.getAsString() : "";
        Integer parsed = parseColor(text);
        if (parsed == null) {
            warnings.add(label + " = \"" + text + "\" is not a #RRGGBB / #AARRGGBB color; using default");
            return def;
        }
        return parsed;
    }

    /** Parses "#RRGGBB" or "#AARRGGBB" (case-insensitive); returns null when invalid. */
    public static Integer parseColor(String text) {
        if (text == null) {
            return null;
        }
        String hex = text.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6 && hex.length() != 8) {
            return null;
        }
        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                value |= 0xFF000000L;
            }
            return (int) value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String defaultTemplate() {
        return """
                // The Explorer's Friend — configuration
                // Invalid values fall back to safe defaults with a warning; the server never
                // crashes because of a typo in this file. Reload at runtime: /efmap reload
                {
                  "web": {
                    // Enable the embedded map web server.
                    "enabled": true,
                    // Bind address. "127.0.0.1" is safe (only reachable locally / via reverse
                    // proxy). Use "0.0.0.0" to expose the map directly to the network.
                    "bind": "127.0.0.1",
                    "port": 8080,
                    // Optional absolute base URL when running behind a reverse proxy,
                    // e.g. "https://map.example.org/". Leave empty for automatic.
                    "public-base-url": "",
                    // Page title shown in the browser.
                    "title": "The Explorer's Friend",
                    // HTTP worker threads. 2 is plenty for small servers.
                    "threads": 2,
                    // gzip-compress JSON/HTML/CSS/JS responses.
                    "gzip": true,
                    // Maximum simultaneous connections.
                    "connection-limit": 64,
                    // Idle connection timeout.
                    "idle-timeout-seconds": 30
                  },
                  "render": {
                    // Background render worker threads. Keep low; rendering is fully async.
                    "workers": 2,
                    // Maximum time per server tick spent taking chunk snapshots (microseconds).
                    "tick-budget-micros": 1500,
                    // Hard cap of chunk snapshots per tick, independent of the time budget.
                    "max-snapshots-per-tick": 4,
                    // Upper bound of queued tile jobs (backpressure limit).
                    "max-queued-tiles": 4096,
                    // Number of zoom-out levels generated above the 1:1 base zoom.
                    "zoom-levels": 4,
                    // Relief shading based on neighbouring surface heights.
                    "height-shading": true,
                    // Darken water with depth.
                    "water-depth-shading": true,
                    // Quiet period after a block change before its tile is re-rendered.
                    "update-debounce-seconds": 5,
                    // A continuously changing tile is re-rendered at least this often.
                    "update-max-delay-seconds": 60,
                    // Render every already-generated region once after the very first start.
                    "full-render-on-first-start": false,
                    // Render chunks when they are first generated/explored.
                    "render-new-chunks": true
                  },
                  "scan": {
                    // Worker threads for the mod-resource scan at startup.
                    "threads": 2,
                    // Dedicated servers do not contain block textures. When enabled, the
                    // official vanilla client JAR is downloaded once from Mojang's servers
                    // (piston-data.mojang.com), verified and cached, to extract block colors.
                    "download-vanilla-assets": true,
                    // Animated textures: use "first_frame" or the "average" of all frames.
                    "animated-textures": "first_frame",
                    // Mod IDs to skip during resource scanning.
                    "exclude-mods": [],
                    // Resource namespaces to skip.
                    "exclude-namespaces": [],
                    // Safety limit: textures larger than this edge length are skipped.
                    "max-texture-edge": 4096,
                    // Safety limit: JARs with more entries than this are skipped.
                    "max-zip-entries": 100000,
                    // Safety limit: maximum decompressed size of a single resource (bytes).
                    "max-entry-bytes": 33554432
                  },
                  "storage": {
                    // Data directory (tiles + caches), relative to the server directory.
                    "data-dir": "explorersfriend",
                    // Maximum tile storage in MB; 0 = unlimited. Oldest tiles are pruned first.
                    "max-tile-cache-mb": 0,
                    // Delete and rebuild all caches on next start.
                    "prune-caches-on-start": false
                  },
                  "worlds": {
                    // Dimensions to render. "*" = all. Example: ["minecraft:overworld"]
                    "enabled": ["*"],
                    // Dimensions to exclude, wins over "enabled".
                    "disabled": [],
                    // Only render within this radius (blocks) around the world spawn; 0 = off.
                    "max-render-radius-blocks": 0
                  },
                  "players": {
                    // Show online players on the map (master switch; false = no data at all).
                    "show": true,
                    // Whether the browser layer toggle starts enabled.
                    "default-visible-in-ui": true,
                    // How often the browser refreshes player positions.
                    "update-interval-seconds": 2,
                    // Hide players with the invisibility effect.
                    "hide-invisible": true,
                    // Hide players in spectator mode.
                    "hide-spectators": true,
                    // Round player positions to this many blocks (privacy blur). 1 = exact.
                    "position-rounding": 1,
                    // Publish positions with this delay (seconds; anti-stalking). 0 = live.
                    "position-delay-seconds": 0,
                    // Show player names next to the head icons.
                    "show-names": true,
                    // Include coordinates in the hover popup.
                    "show-coordinates": true,
                    // Replace names with "Player 1", "Player 2", ... on the map.
                    "anonymize-names": false,
                    // Allow fetching skins from Mojang's texture servers (cached, rate-limited).
                    // Disabled or offline-mode servers use a neutral default head.
                    "allow-external-skin-lookup": true,
                    // Re-check a player's skin after this many hours.
                    "skin-cache-hours": 24,
                    // Players never shown on the map (names or UUIDs; per-player opt-out).
                    "hidden-players": [],
                    // Dimensions excluded from the live player layer.
                    "disabled-worlds": []
                  },
                  "logging": {
                    // Interval of aggregated progress lines during scans and renders.
                    "progress-interval-seconds": 5,
                    // Verbose per-file/per-resource logging.
                    "debug": false
                  },
                  "blocks": {
                    // Color used for blocks whose textures could not be resolved.
                    "unknown-block-color": "#7f7f7f",
                    // Blocks that must never appear on the map (rendered as the block below).
                    "exclude-blocks": []
                  },
                  "claims": {
                    // Claim overlay (FTB Chunks, Open Parties and Claims, JSON import).
                    "enabled": true,
                    // Whether the browser layer toggle starts enabled.
                    "default-visible-in-ui": true,
                    // Authoritative re-sync interval per provider (change events refresh sooner).
                    "refresh-interval-seconds": 60,
                    // Fill opacity of claim areas (they are always semi-transparent).
                    "fill-opacity": 0.30,
                    // Border opacity (1.0 = fully opaque, recommended).
                    "border-opacity": 1.0,
                    // Border width in pixels at base zoom (scaled sensibly when zooming out).
                    "border-width": 2,
                    // Privacy: what claim details the browser may see.
                    "show-owner": true,
                    "show-name": true,
                    "show-team": true,
                    // Claim providers to use. "*" = every detected one.
                    // Known ids: "ftbchunks", "openpartiesandclaims", "jsonimport"
                    "enabled-providers": ["*"],
                    // Dimensions whose claims are never shown.
                    "disabled-worlds": [],
                    // Hard cap of claims in one HTTP response.
                    "max-claims-per-response": 5000,
                    // Fallback color when neither claim, team, owner nor a stable identity
                    // provides one.
                    "default-color": "#4080ff"
                  },
                  "markers": {
                    // Marker overlay (commands + named banners).
                    "enabled": true,
                    "default-visible-in-ui": true,
                    "banners-default-visible-in-ui": true,
                    // Limits.
                    "max-per-player": 30,
                    "max-total": 5000,
                    // Players (permission level 0) may create/manage their own markers.
                    "allow-player-creation": true,
                    // Banners renamed in an anvil become map markers when placed.
                    "allow-banner-markers": true,
                    // Breaking a banner removes its marker (false = marker stays as orphan).
                    "remove-marker-with-banner": true,
                    // Show the creator name in marker popups.
                    "show-creator": false,
                    // Show coordinates in marker popups.
                    "show-coordinates": true,
                    // Dimensions where markers are disabled.
                    "disabled-worlds": [],
                    // Batched save interval for marker changes (plus an immediate save on shutdown).
                    "save-interval-seconds": 30
                  },
                  "performance": {
                    // Pause backlog rendering automatically while the server is overloaded.
                    "auto-throttle": true,
                    // Average MSPT above which background rendering pauses...
                    "mspt-pause-threshold": 45,
                    // ...and below which it resumes.
                    "mspt-resume-threshold": 35
                  }
                }
                """;
    }
}
