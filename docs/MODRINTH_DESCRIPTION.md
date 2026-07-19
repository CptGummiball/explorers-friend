# The Explorer's Friend

**A lightweight, fully server-side browser world map for Fabric servers.**

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
- **Markers** via `/efmap marker ...` with a built-in icon library, plus
  **banner markers**: rename a banner in an anvil and place it — it appears on the
  map with its actual pattern; breaking it removes it
- Layer panel in the web UI (claims / markers / banners / players), state persists
  in the browser

🔒 **Safe and private by default.** Binds to `127.0.0.1` until you decide otherwise
(reverse-proxy guide included). Strict path validation, zip/PNG-bomb limits, bounded
connections. Player layer: invisible/spectator filtering, position rounding and delay,
name anonymization, per-player opt-out — or turn it off entirely.

📊 **Observable.** Structured console summaries, `/api/v1/status` JSON diagnostics and
an optional Prometheus `/metrics` endpoint.

## Quick start

1. Drop the JAR matching your Minecraft version (see the table below) +
   [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
2. Start the server → map at `http://localhost:8080/`.
3. Render the already-explored world: `/efmap render minecraft:overworld`

On dedicated servers the first start downloads the vanilla client JAR once from
Mojang's official servers (block textures are not in the server JAR; SHA-1-verified,
cached, can be disabled).

## Minimum requirements

Seven release files cover every stable Minecraft version from **1.21.1 to 26.2** —
install exactly the one matching your server (each file lists its supported game
versions):

| Minecraft | Java | Fabric Loader |
| --- | ---: | --- |
| 1.21.1 – 1.21.8 | 21 | ≥ 0.16 |
| 1.21.9 – 1.21.10 | 21 | ≥ 0.17.0 |
| 1.21.11 | 21 | ≥ 0.17.3 |
| 26.1 – 26.2 | 25 | ≥ 0.19 |

- **Fabric API** is required on every version.
- Optional integrations: FTB Chunks, Open Parties and Claims (availability differs
  per Minecraft version — see the compatibility notes in the repository's
  `docs/MULTIVERSION.md`; the JSON claim import works everywhere)

## Links

- Source, issues, full documentation (configuration reference, reverse proxy,
  performance, API): https://github.com/CptGummiball/explorers-friend

License: MIT. Original implementation — no code or assets from other map projects.
