# Changelog

## 0.3.0 — 2026-07-19

### Added
- **Multi-version support**: seven release artifacts now cover every stable Minecraft
  release from 1.21.1 through 26.2 (1.21.1, 1.21.2–1.21.4, 1.21.5–1.21.8,
  1.21.9–1.21.10, 1.21.11, 26.1–26.1.2, 26.2). Version families were derived by compiling identical adapter
  source against every release and dedicated-server smoke tests — not guessed
  (the 1.21.5 `setBlockState` mixin boundary is only visible at runtime). See docs/MULTIVERSION.md for the
  download table, family boundaries and per-version claim-provider availability.
- **26.x era port**: full port to unobfuscated Minecraft (official class names,
  Java 25, loom no-remap pipeline, `official` access-widener namespace, the new
  PermissionSet command system, three-argument CHUNK_LOAD event).
- `dist/` release pipeline: `packageAllVersions` + `verifyAllArtifacts` produce all
  jars with `checksums-sha256.txt`, `release-manifest.json` (honest per-artifact
  "tested" flags fed by the smoke-test results) and `compatibility.json`.
- Per-artifact dedicated-server smoke test harness (`scripts/smoke.py`).

### Changed
- Restructured into a Gradle multi-project: a Minecraft-free `common` core (renderer,
  web server, caches, overlay models — bundled into every artifact) plus thin
  per-family platform adapters under `platforms/`. No runtime version checks, no
  reflection: version differences are resolved at compile time per module.
- CI now builds the whole version matrix on JDK 21+25 and uploads all artifacts.

### Notes
- Block-color caches are keyed by jar-set hash; switching Minecraft versions triggers
  a clean re-scan automatically. Markers, config and manual colors carry over.
- Claim adapters per version: FTB Chunks only where Fabric builds exist (1.21.1;
  1.21.11 deferred), OPAC on the 1.21.x targets, JSON import everywhere.

## 0.2.1 — 2026-07-19

### Fixed
- **Server deadlock (watchdog kill) on chunk load**: the banner-marker heal path ran
  `world.getBlockState`/`getBlockEntity` inside the `CHUNK_LOAD` callback. During that
  callback the chunk is not yet registered in the chunk manager, so the world lookup
  landed in `getChunkBlocking` on the server thread — which waited on itself.
  The heal path now reads exclusively from the chunk instance the event delivers
  (bounds-checked), and every other banner check resolves its chunk non-blocking
  (`ChunkStatus.FULL, create=false`); unloaded positions are skipped instead of
  blocking or being misread as "banner gone". Audited the whole project for further
  blocking world access in event handlers — none remain.

### Improved
- Chunk loads now also register named banners found among the chunk's own block
  entities: banners placed by dispensers, other mods, or before the mod was installed
  appear on the map automatically.

## 0.2.0 — 2026-07-18

### Added
- **Overlay architecture**: revisioned, spatially indexed layers (`/api/v1/*`) with
  ETag/304, bounding-box filters, world separation, strict validation and result caps.
  Base tiles are never re-rendered by overlay changes.
- **Claims layer**: adapter-based integrations for **FTB Chunks** and **Open Parties
  and Claims** (official APIs, compile-only, detected at startup — absent mods cause
  zero class loading) plus a **JSON import provider** as a bridge for non-Fabric
  systems (GriefPrevention has no Fabric port — documented in docs/CLAIM_PROVIDERS.md).
  Chunk→rectangle merging, color priority chain (claim → team → owner → deterministic
  → default), semi-transparent fills with opaque borders, event-driven refreshes with
  debouncing, exponential backoff, privacy switches (owner/name/team), per-world
  exclusions.
- **Live player layer v2**: revision/delta responses, player-head icons composed from
  the profile skin (host-pinned to Mojang's texture CDN, cached in memory + disk,
  offline-safe default head), `PlayerVisibilityProvider` API for vanish systems,
  position delay, name anonymization, per-player opt-out, per-world exclusion.
- **Markers**: persistent, validated store (schema-versioned JSON with rolling backup
  and quarantine recovery), full `/efmap marker` command tree (add/add-at/list/info/
  rename/icon/move/hide/show/description/category/categories/icons/teleport) with tab
  completion, quoted names, per-player and total limits, own/foreign permissions.
- **Banner markers**: anvil-renamed banners become markers on placement, disappear on
  break/explosion (configurable), heal on chunk load; icons are composed server-side
  from the actual banner patterns via the vanilla assets, deduplicated by design hash.
- **Built-in icon library**: 23 original SVG icons served from a fixed whitelist.
- **Web UI**: layer panel (claims / markers / banner markers / players / names) with
  localStorage persistence, hover + tap tooltips (stacked for overlapping claims),
  marker clustering at low zoom, player heads with direction indicator.
- **Auto-throttle**: rendering pauses/resumes automatically based on average MSPT.
- **`/metrics`**: Prometheus text endpoint (can be disabled).
- **`/efmap web restart`**: stops and restarts the embedded web server with a freshly
  loaded configuration — bind address and port changes apply without a server restart
  (runs on a worker; the server thread is never blocked).

### Decisions
- WebP tiles remain rejected: the JDK has no WebP encoder and bundling native codecs
  contradicts the zero-dependency design; PNG + ETag/304 + empty-tile elision covers
  the bandwidth goal.

## 0.1.0 — 2026-07-18

First release. Minecraft 1.21.1 · Fabric · Java 21.

### Added
- Fully server-side browser world map with an embedded, dependency-free HTTP server
  (bounded threads/connections, gzip, ETag/304, strict tile-URL validation) and an
  original canvas web UI (pan/zoom, dimension switcher, coordinates, share links,
  live player markers).
- Automatic block colors for **all** mods: per-start JAR inventory with SHA-256
  content identity (nested jar-in-jar included, persistent cache, dedupe of identical
  content, cleanup of removed versions) and a blockstate → model → texture resolver
  with cycle guards; representative colors computed in linear color space with
  alpha-weighted, outlier-trimmed averaging; `.mcmeta` animation handling
  (first frame or frame average).
- Content-addressed texture color cache and a block-color cache keyed by
  (mod-set hash, algorithm version, animation mode) — unchanged setups skip the scan
  entirely; adding one mod re-scans in milliseconds.
- Biome tinting (grass/foliage/water) from registry climate data using the vanilla
  colormaps extracted at runtime from the user's own game JAR (never redistributed);
  swamp/dark-forest modifiers included.
- Rendering pipeline: 512×512 region tiles (1 px/block), relief and water-depth
  shading, translucent-block compositing, configurable zoom-out pyramid, empty tiles
  deleted instead of written, atomic file writes everywhere.
- Event-driven updates: a single mixin funnels every block change into a debounced
  dirty tracker; live chunk snapshots run on the server thread under hard µs/count
  budgets; unloaded chunks render from region files on workers (zero chunk loads).
- Resumable, pausable, cancelable full renders (`/efmap render`) with persistent
  progress, priority queue with per-tile job merging and backpressure, per-chunk
  error isolation with bounded retries, stuck-worker watchdog.
- Incremental `/efmap update` (region file mtime vs. tile).
- Commented, validated JSONC configuration with safe fallbacks; manual color
  overrides file with live reload.
- `/efmap` command tree: status, render, update, pause, resume, cancel, reload,
  scan status/mods, colors reload, cache stats/prune, web status.
- Minimal public API: block color providers, scan-complete and tile-rendered events.
- Structured logging channels (`[ExplorersFriend/Scanner|Colors|Renderer|Cache|Web]`)
  with rate-limited progress lines and summaries; `/api/status` JSON diagnostics.
- Hardening: zip-bomb and PNG-bomb limits, path-traversal-proof serving, NBT
  depth/size caps, cache corruption quarantine + self-heal, multi-instance file lock
  with read-only fallback, ordered shutdown persisting all state.
- Test suite: 60+ unit/integration tests including synthetic region files, hostile
  archives, broken JSON/PNG fixtures and a live HTTP server.
