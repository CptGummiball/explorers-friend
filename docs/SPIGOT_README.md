# The Explorer's Friend — live browser world map for Spigot & Paper

**A lightweight, fully server-side web map plugin. One jar, every Minecraft
version from 1.21.1 to 26.x. No client mod, no NMS, no database.**

Your players open the map in their browser — pan, zoom, switch dimensions,
watch each other move in real time. You install a single plugin and get a
rendered topographic map of everything that has ever been explored.

## Why this plugin

- 🗺️ **Real topographic tiles** — relief shading, water depth, biome-tinted
  grass and foliage. Full renders read the region files directly on worker
  threads: **not a single chunk is loaded** and the main thread stays untouched.
- ⚡ **Built for busy servers** — live updates come from thread-safe chunk
  snapshots with hard per-tick budgets, all queues are bounded, and rendering
  pauses automatically while your TPS is under pressure.
- 🎨 **Custom content just works** — block colors are computed from the actual
  textures (vanilla assets are fetched once from Mojang, SHA-verified; plugin
  jars are scanned for resources too). Manual color overrides included.
- 🧭 **One jar for every version** — pure Bukkit API (`api-version: 1.21`),
  no version-specific NMS hacks. The same file runs on 1.21.1 through 26.x,
  on Spigot, Paper and Paper forks (Purpur & friends). Paper is detected
  automatically.

## Features

- **Live player layer** with real skin heads (privacy options: hide invisible/
  spectators, position rounding & delay, name anonymization, per-player opt-out)
- **GriefPrevention claims** on the map — detected automatically (softdepend),
  shown as exact block rectangles including subclaims, with live updates on
  create/resize/delete. No GriefPrevention? The layer simply stays off.
- **Markers** via `/efmap marker add|list|remove` with a built-in icon library
  and your own custom icons (drop PNGs into `plugins/ExplorersFriend/icons/`)
- **JSON claim import** as a bridge for any other protection plugin
- **Web UI layer panel** — every layer individually toggleable, hover tooltips,
  clustering, share links; settings persist in the browser
- **Ops tooling** — `/efmap status|render|pause|resume|cancel`, HTTP ETag
  caching, JSON status API, optional Prometheus `/metrics`
- **Privacy-hardened & secure by default** — web server binds to `127.0.0.1`
  until you expose it (reverse-proxy guide included), strict path validation,
  bounded connections

## Quick start

1. Drop `explorersfriend-spigot-paper-<version>.jar` into `plugins/`.
2. Start the server. First start downloads the vanilla client jar once from
   Mojang (block textures are not in the server jar; SHA-1-verified, cached).
3. Open **http://localhost:8080/** — then render what's already explored:
   `/efmap render minecraft_overworld`

Configuration lives in `plugins/ExplorersFriend/config.jsonc` (fully commented).
Permissions use the standard Bukkit system (`explorersfriend.command.*`,
defaults: admin commands = op, marker creation = everyone, configurable).

## Good to know

- Java 21 for Minecraft 1.21.x servers, Java 25 for 26.x — same as the server itself.
- On Minecraft 26.x, Mojang removed the built-in worldgen data files, so biome
  tinting uses neutral defaults there (map stays fully functional; 1.21.x is unaffected).
- Markers, config, manual colors and tiles are portable: you can move a world
  from a Fabric/NeoForge server to Paper (or back) and keep your map data — the
  same map core runs on all of them.

## Links

- Source, documentation and issue tracker:
  https://github.com/CptGummiball/explorers-friend
- All-platform downloads (Fabric, Quilt, NeoForge): see the Modrinth page or
  GitHub releases. This page ships the Spigot/Paper plugin build.

*License: MIT. Fully original code — no Pl3xMap/Dynmap/BlueMap code or assets.*
