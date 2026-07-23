# The Explorer's Friend

**A lightweight, fully server-side browser world map** for **Fabric, Quilt,
NeoForge, Spigot and Paper** servers.

Install one JAR on the server — players join with a completely vanilla client and open
the live map in their browser. No client mod, no custom network protocol, nothing for
players to install. (Putting the JAR into a client instance is harmless: singleplayer
worlds get a map through the integrated server.)

## Why another web map?

🎨 **Mod blocks just work.** On startup the mod inventories every installed mod JAR
(SHA-256 content identity, nested jar-in-jar included), resolves each registered block
through its blockstate → model → texture chain and computes a representative map color
from the actual texture — in linear color space, alpha-aware, with outlier trimming.
Everything is cached content-addressed: unchanged mods are never scanned twice, and a
typical modpack re-scan after adding one mod takes milliseconds.

⚡ **Minimal server impact.** Nothing heavy ever runs on the server thread. Live world
access is limited to hard-budgeted chunk snapshots (default ≤ 1.5 ms and ≤ 4 chunks per
tick); full renders read region files from disk on worker threads **without loading a
single chunk**. All queues are bounded with backpressure and job merging, and an
MSPT-based auto-throttle pauses background rendering while the server is busy.

🗺️ **Complete map features.**
- Topographic tiles with relief and water-depth shading, multiple zoom levels,
  incremental event-driven updates (block changes → debounced re-render)
- Resumable, pausable full renders (`/efmap render`)
- **Claims overlay**: auto-detected integrations for **FTB Chunks** and
  **Open Parties and Claims** (official APIs, nothing bundled) plus a JSON import
  bridge for other systems — semi-transparent fills, team colors, hover details
- **Live players** with real skin heads (cached, privacy-hardened, vanish-API)
- **Markers** via `/efmap marker ...` with a built-in icon library and
  user-supplied custom icons, plus **banner markers**: rename a banner in an anvil
  and place it — it appears on the map with its actual pattern; breaking it removes it
- **Waystones layer** (Fabric/Quilt/NeoForge, with the Waystones mod): named
  waystones as their own toggleable layer — sharestones stay private
- Layer panel in the web UI (claims / markers / banners / waystones / players), state persists
  in the browser

🔒 **Safe and private by default.** Binds to `127.0.0.1` until you decide otherwise
(reverse-proxy guide included). Strict path validation, zip/PNG-bomb limits, bounded
connections. Player layer: invisible/spectator filtering, position rounding and delay,
name anonymization, per-player opt-out — or turn it off entirely.

📊 **Observable.** Structured console summaries, `/api/v1/status` JSON diagnostics and
an optional Prometheus `/metrics` endpoint.

## Quick start

1. Pick the file for your **platform and Minecraft version** (see below) and drop
   it into `mods/` (Fabric/Quilt/NeoForge) or `plugins/` (Spigot/Paper).
   Fabric/Quilt additionally need [Fabric API](https://modrinth.com/mod/fabric-api).
2. Start the server → map at `http://localhost:8080/`.
3. Render the already-explored world: `/efmap render minecraft:overworld`
   (Spigot/Paper: `/efmap render minecraft_overworld`)

On dedicated servers the first start downloads the vanilla client JAR once from
Mojang's official servers (block textures are not in the server JAR; SHA-1-verified,
cached, can be disabled).

## Choosing your file

Every stable Minecraft version from **1.21.1 to 26.2** is covered. Install exactly
**one** Explorer's Friend file matching your platform and version:

| Platform | File | Minecraft | Java |
| --- | --- | --- | ---: |
| **Fabric / Quilt** | `…-fabric-1.21.1` / `-1.21.2-1.21.4` / `-1.21.5-1.21.8` / `-1.21.9-1.21.10` / `-1.21.11` | as named | 21 |
| **Fabric / Quilt** | `…-fabric-26.1` / `-26.2` | 26.1–26.2 | 25 |
| **NeoForge** | `…-neoforge-1.21.1` / `…-neoforge-26.2` | 1.21.1 / 26.2 | 21 / 25 |
| **Spigot / Paper / Purpur** | `…-spigot-paper` — **one file for all versions** | 1.21.1 – 26.x | 21 / 25 |

Loader minimums (Fabric): ≥0.16 (1.21.1–1.21.8), ≥0.17.0 (1.21.9+), ≥0.17.3
(1.21.11), ≥0.19 (26.x). **Fabric API** is required on Fabric/Quilt only.

Optional integrations per platform:
- Fabric/Quilt: FTB Chunks (1.21.1, 1.21.11), Open Parties and Claims (all),
  GOML/Common Protection API, Waystones layer, LuckPerms permission nodes
- NeoForge: FTB Chunks + Open Parties and Claims (1.21.1), OPAC (26.2), Waystones
- Spigot/Paper: **GriefPrevention** claims
- The JSON claim import works everywhere.

## Links

- Source, issues, full documentation (configuration reference, reverse proxy,
  performance, API): https://github.com/CptGummiball/explorers-friend

License: MIT. Original implementation — no code or assets from other map projects.
