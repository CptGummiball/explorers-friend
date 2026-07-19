# Pl3xMap — Deep Reference Analysis

Analyzed repo: `granny/Pl3xMap`, commit `0ae3449c` (2026-07-11, "Update Adventure API back to version 5 (#161)"), targeting Minecraft 26.2 snapshots.
Local checkout: `scratchpad/Pl3xMap`. Purpose: reference input for a clean-room-style reimplementation (Fabric-focused). **Do not copy code; see §13 for what must not be reused at all.**

---

## 1. Module & package structure, build, dependencies

### Modules (`settings.gradle.kts`)
| Module | Purpose |
|---|---|
| `core` | Platform-agnostic engine: `net.pl3x.map.core` — world/region/chunk reading, renderers, tile IO, Undertow webserver, cloud commands, markers/layers API, configs, event system, player abstraction, scheduler. Contains its own MCA parser (no server chunk access for rendering). |
| `bukkit` | Thin platform layer (~14 classes): `Pl3xMapBukkit` (JavaPlugin + Listener), `Pl3xMapImpl` (extends `Pl3xMap`), `BukkitWorld`, `BukkitPlayer`, `BukkitNetwork` (plugin messaging), `BukkitCommandManager` (cloud-paper), `SchedulerUtil` (Folia-aware: uses GlobalRegionScheduler when Folia detected). Built with paperweight-userdev. |
| `fabric` | Mod for client + dedicated server; entrypoints, 2 mixins, access widener, custom network payloads, cloud-fabric commands, adventure-platform-fabric. |
| `forge` | Present in tree but **disabled** (commented out of `settings.gradle.kts`). |
| `webmap` | TypeScript + Leaflet frontend; built by webpack via `com.github.node-gradle.node` (downloads Node 22.20.0); `dist/` is renamed to `web/` and packed as a Java resource of the `:webmap` "java" project, which `core` depends on. At runtime core extracts `/web/*` from its jar into the configured web dir (`FileUtil.extractDir`). |

### Jar assembly
- `core` uses Shadow (`shadowJar`), relocating most deps to `libs.*` (undertow, caffeine, simpleyaml, xnio, jboss, wildfly, lz4 (`net.jpountz`), checkerframework…) — deliberately NOT relocating `net.kyori` (adventure), `org.incendo` (cloud), `io.leangen.geantyref`. Manifest gets `Main-Class` and `Git-Commit` (indra-git).
- `fabric` builds a merged jar (`mergeShadowAndJarJar`, pattern explicitly copied from BlueMap's fabric build): shadowJar (with core included) merged with loom's remapped jar (which contains `META-INF/jars/**` = jar-in-jar cloud-fabric + adventure-platform-fabric) → output `*.jar.tmp`.
- Root project's `jar` task then merges **bukkit jar + fabric jar.tmp into one uber-jar** (self-described as "janky, but it works"), merging manifests; the single artifact is published to Modrinth for bukkit/spigot/paper/purpur/folia/fabric/quilt simultaneously.
- Version scheme: `<minecraftVersion>-<NEXT_BUILD_NUMBER|SNAPSHOT>`. No tests exist anywhere in the repo (no `src/test` in any module).

### Dependencies (from `gradle/libs.versions.toml`) with licenses
| Dependency | Version | License |
|---|---|---|
| io.undertow:undertow-core | 2.4.1.Final | Apache-2.0 |
| org.incendo cloud-core / -brigadier / -paper / -minecraft-extras / -fabric / -processors-confirmation | 2.0.0 / 2.0.0-SNAPSHOT / rc.1 | MIT |
| net.kyori adventure-api / -minimessage / -serializer-plain | 5.0.0-SNAPSHOT | MIT |
| adventure-platform-facet / -bukkit | 4.4.1 | MIT |
| adventure-platform-fabric | 7.0.0-SNAPSHOT | MIT |
| com.github.ben-manes.caffeine | 3.2.3 | Apache-2.0 |
| de.bluecolored:bluenbt (BlueMap's NBT lib) | 3.5.0 | MIT |
| at.yawk.lz4:lz4-java | 1.11.0 | Apache-2.0 |
| com.github.Carleslc.Simple-YAML (via jitpack) | 1.8.4 | MIT |
| gson (compileOnly, provided by Mojang) | 2.13.2 | Apache-2.0 |
| guava (compileOnly, provided) | 33.5.0-jre | Apache-2.0 |
| log4j-core (compileOnly) | 2.25.2 | Apache-2.0 |
| org.jspecify | 1.0.0 | Apache-2.0 |
| fabric-loader / fabric-api | 0.19.3 / 0.152.2+26.2 | Apache-2.0 |
| Leaflet (checked into `webmap/public/leaflet.js`) | 1.9.4 | BSD-2-Clause |
| bStats (`core/.../metrics/Metrics.java`, vendored) | — | MIT |
| Build plugins: shadow 8.3.9, fabric-loom 1.17, paperweight 2.0.0-beta.21, minotaur (Modrinth publish), node-gradle 7.0.2, indra-git | | Apache-2.0/MIT |

---

## 2. Fabric integration

- **`fabric.mod.json`**: id `pl3xmap`, license MIT, `environment: "*"`; entrypoints `client` → `net.pl3x.map.fabric.client.Pl3xMapFabricClient` (ClientModInitializer), `server` → `net.pl3x.map.fabric.server.Pl3xMapFabricServer` (DedicatedServerModInitializer, extends core `Pl3xMap`). Depends: fabricloader, fabric-api, minecraft `~version`, **java >= 25**, cloud, adventure-platform-fabric (both jar-in-jar'd).
- **Mixins** (2 total, split configs `pl3xmap.server.mixins.json` / `pl3xmap.client.mixins.json`):
  - `server/mixin/MixinServerPlayer` (on `ServerPlayer`): injects at TAIL of `addAdditionalSaveData`/`readAdditionalSaveData` to persist a `pl3xmap.hidden` boolean in player NBT; exposes it via duck interface `AccessServerPlayer` (`pl3xMap$isHidden/setHidden`). Backs the `/map hide|show` commands.
  - `client/mixin/MapInstanceMixin` (on `MapTextureManager.MapInstance`): intercepts `updateTextureIfNeeded()` (HEAD, cancellable) to replace the **in-game map item texture** with pixels fetched from the server's web tiles (via `TileManager` HTTP cache); asks the server for map metadata via custom payload; leaves vanilla rendering when disabled/not on a Pl3xMap server. Duck interface `MapInstance`.
- **Access widener** (`pl3xmap.accesswidener`, v2 official): client → `MapTextureManager$MapInstance` class + `updateTextureIfNeeded()` + `MapTextureManager.maps` field; server → `Biome$ClimateSettings` + `Biome.climateSettings` (for downfall/humidity), `SavedDataStorage.dataFolder` (to locate the `region/` directory), `CommandSourceStack.source`.
- **Lifecycle hooks** (fabric-api events, no mixins needed): `ServerLifecycleEvents.SERVER_STARTED` → `enable()` + register network; `SERVER_STOPPING` → `disable()`; `ServerLevelEvents.LOAD/UNLOAD` → register/unregister `FabricWorld`; `ServerTickEvents.END_SERVER_TICK` → ticks the core `Scheduler` + fires `ServerLoadedEvent` on first tick; `ServerPlayConnectionEvents.JOIN/DISCONNECT` and `ServerPlayerEvents.AFTER_RESPAWN` → player registry.
- **Networking**: 4 custom payloads (`ServerboundServerPayload` ↔ `ClientboundServerPayload` = protocol handshake + web address; `ServerboundMapPayload` ↔ `ClientboundMapPayload` = map id → scale/centerX/centerZ/world). Protocol constant 3 (`core/.../network/Constants.java`). Bukkit has a mirror implementation over plugin messaging channels.
- **Client mod**: keybinding (M) to toggle, `TileManager` fetches/caches PNG tiles from `Config.WEB_ADDRESS`, own 1-thread executor ("Pl3xMap-Update"), scheduler ticked every 20 client ticks.
- **`FabricWorld`**: resolves region dir via access-widened `dataFolder`; registers all biomes from the dynamic registry with colors (see §4); world border, min/max Y, logical height, `hasCeiling` from `DimensionType`; seed hashed via `BiomeManager.obfuscateSeed`.
- **`Pl3xMapFabricServer`** also implements FlowerMap support (`getFlower()`): scans each biome's bonemeal vegetation `ConfiguredFeature`s (credited to Draradech/FlowerMap, CC0) with an unsynchronized `LinkedHashMap` cache; and `loadBlocks()` = iterate `Registries.BLOCK`, register `defaultMapColor().col` per block id.

---

## 3. Rendering pipeline end-to-end

**Source of truth: region files on disk, not live chunks.** Core has its own MCA/Anvil reader; the map only sees what Minecraft has saved to `region/*.mca`.

1. **Trigger** (see §Update triggering below) → `RegionProcessor.addRegions(world, points)` adds `Ticket(world, region)` to a `ConcurrentLinkedDeque` (with an O(n) `contains` dedupe check).
2. **`RegionProcessor`** (single thread "Pl3xMap-Processor"): self-rescheduling loop (initial 10 s delay after enable, then every 5 s): drains tickets into per-world sets, orders each world's regions with a **`SpiralIterator` around world spawn** (failsafe after 1M misses), sets up `Progress`, then submits one **`RegionScanTask` per region** to the shared render executor and `join()`s the combined future. After each region: store scan-start time into `RegionModifiedState`; optional `System.gc()` (`GC_WHEN_RUNNING`); after the world: `world.cleanup()` (invalidate region cache, save `.rms`), optional `System.gc()` (`GC_WHEN_FINISHED`, default on).
3. **Render executor**: `ForkJoinPool` "Pl3xMap-Renderer", size = `settings.performance.render-threads` (default −1 → half of available cores, clamped 1..cores).
4. **`RegionScanTask.run()`** (per region, on a render thread):
   - constructs one `Renderer` instance per configured renderer (reflection via `RendererRegistry.createRenderer`), plus `BlockInfoRenderer` if the blockinfo UI is enabled;
   - `allocateImages()` — one `TileImage` (raw `int[512*512]` ARGB) per renderer;
   - `loadRegion()` — `World.getRegion()` via **Caffeine `LoadingCache` (max 100 regions, expireAfterWrite 1 min)** → `Region.loadChunks()`: single `RandomAccessFile`, reads the 4-byte chunk offset table entry per index, seeks, decompresses (GZIP/DEFLATE/LZ4/none per compression byte; `CompressionType`), deserializes NBT with **BlueNBT** into version-specific data classes — `ChunkLoader` picks `Chunk_1_18` (DataVersion ≥2844), `Chunk_1_16` (≥2500), `Chunk_1_15` (≥2200), `Chunk_1_13` (≥1519), optimistic "last used loader" retry logic (all of this BlueMap-derived);
   - `Chunk.populate()` (once per chunk): for each of 256 columns compute `BlockData`: start at `WORLD_SURFACE` heightmap value (or top of sections if heightmap missing); for ceiling worlds (nether) descend from logical height until air first; then descend until first block with `color() > 0`, recording topmost fluid (`fluidY`/`fluidstate`), collecting translucent glass tints (0x99 alpha) into a `LinkedList`, `isFlat` blocks (carpets etc.) adjust Y; biome resolved lazily on first access via `BiomeManager.getBiome` (Mojang's 4x4x4 fuzz/voronoi algorithm with hashed seed) against the section biome palette;
   - `scanRegion()` — each renderer's `scanData(region)` iterates 32×32 chunks × 16×16 columns calling `scanBlock(...)`; `Pl3xMap.api().getRegionProcessor().checkPaused()` (busy-wait sleep 50 ms) is called **per block**;
   - `saveImages()` — per renderer `TileImage.saveToDisk()`, then update `RegionModifiedState` with `System.currentTimeMillis()`.

### Renderers (`core/.../renderer/`, registered in `RendererRegistry`)
| Key | Class | Behavior |
|---|---|---|
| `basic` | `BasicRenderer` | `basicPixelColor`: block color (biome-tinted) + heightmap shade + fluid handling + glass tinting. |
| `biomes` | `BiomeRenderer` | Flat per-biome color from `ColorsConfig.BIOME_COLORS`, heightmap shading on land. |
| `night` | `NightRenderer` | Reuses basic renderer's pixel (same task) and multiplies a darkness overlay from block light (`calculateLight`, cap 0xCC, lava special-cased to light 15). |
| `inhabited` | `InhabitedRenderer` | Heat map: HSB-lerp blue→red by `chunk.getInhabitedTime()/3600000`, blended at low alpha over basic pixel. |
| `flowermap` | `FlowerMapRenderer` | Gray base; per-column asks platform `getFlower()` (biome bonemeal feature) and colors from a hardcoded flower→color map (adapted from Draradech/FlowerMap, CC0). |
| `vanilla` | `VanillaRenderer` | Mimics vanilla map item look: `old_school` heightmap, own column scan. |
| `vintage_story` | `VintageStoryRenderer` | Vintage-Story-style hill shading: builds full 512² pixel+shadow maps, slope/altitude diff of NW/NE/SW neighbors, box-blurs shadow map (`BlurTool`), multiplies. |
| `nether_roof` | `NetherRoofRenderer` | Renders above-the-bedrock-roof surface (heightmap "none"). |
| `blockinfo` | `BlockInfoRenderer` | Not an image: binary buffer (12-byte header + 4 bytes/column packing block index/biome index/height) gzip'd to `<zoom>/blockinfo/<x>_<z>.pl3xmap.gz`, consumed by the web UI for hover info. |

- **Heightmap shading**: pluggable `Heightmap` registry, 14 variants (`modern` default, `old_school`, `even_odd_*`, `high_contrast`, `low_contrast`, `vanilla`, `none`). They compare `blockY` of the column vs west/north neighbors (fetched via `World.getChunk` → region cache, crossing region borders) and return a black overlay with alpha stepped in 0x00–0x44, blended over the pixel.
- **Fluids**: config `render.translucent-fluids` (default on): "fancy" mode lerps water color toward black by depth (cubic/quintic easing) and sets depth-based alpha, then alpha-blends over the land pixel; lava similar without biome color. Flat mode: checkerboard-ish shading by depth parity (vanilla map look). Water color is biome water color with neighbor blending.
- **Glass**: `render.translucent-glass` (default on): glass blocks above the surface each contribute their color at 0x99 alpha, blended top-down.
- **Biome blend**: `render.biome-blend` radius (0–5-ish) — `Colors.sampleNeighbors` averages grass/foliage/water colors over (2r)² neighbor columns, each requiring `getChunk().getData()` lookups (hot path).

### Tiles, zoom levels, downscaling
- Tile = **512×512 px, 1 px = 1 block at zoom 0, exactly one region file**. Path: `web/tiles/<world>/<zoom>/<renderer>/<regionX>_<regionZ>.png` (world name `:` → `-`).
- Zoom-out levels 0..`zoom.max-out` (default 3) are written **immediately by the same region task**: for zoom z, the region's pixels are downsampled by factor 2^z (box average of the 2^z × 2^z block; pixels equal to 0 skipped so neighbors aren't erased) and written into the **shared** tile file `(<x>>>z)_(<z>>>z).png` — i.e. every zoomed tile is **read from disk, decoded, partially overwritten, re-encoded** under a per-path fair `ReentrantReadWriteLock` held in a **static, never-pruned `ConcurrentHashMap<Path, ReadWriteLock>`** (`TileImage.FILE_LOCKS`; `BlockInfoRenderer` has its own copy of this pattern).
- Extra zoom-in (`zoom.max-in`, default 2) is purely client-side stretching (Leaflet `maxZoom = maxOut + maxIn`, `zoomOffset = -maxIn`, `maxNativeZoom = maxOut`); zoom numbering in URLs is *reversed* (`ReversedZoomTileLayer._getZoomForUrl`: 0 = sharpest).
- Image IO: `javax.imageio` via pluggable `IO.Type` registry (`png` default; `bmp`, `gif`, `jpg` with `settings.web-directory.tile-quality` compression); write goes to `.tmp` then `FileUtil.atomicMove` (guava move + `ATOMIC_MOVE`, AccessDenied retry ×5 with 20 ms sleeps — a Windows-oriented workaround).

### Update triggering (how changed areas get re-rendered)
- **No block/chunk event hooks at all.** Three mechanisms only:
  1. On world register: `World.init()` → `listRegions(false)` = all `r.X.Z.mca` whose file mtime > stored per-region timestamp in `RegionModifiedState` (binary gzip file `web/tiles/<world>/.rms`, map long(regionPos)→long(millis), loaded at startup, saved on cleanup/shutdown).
  2. **`RegionDoubleChecker`** (1 thread): first run 250 s after enable, then every 30 s: stats **every** region file of every enabled world and queues those with mtime > `.rms` state.
  3. Commands `/map fullrender <world>` (all regions, ignore timestamps) and `/map radiusrender <world> <radius> [center]`.
- A `RegionFileWatcher` (Java `WatchService` on the region dir) exists but is **disabled** — the field/start calls are commented out in `World`, and its `TimerTask` body is commented out too.
- Consequence: map freshness is bounded by Minecraft's autosave interval + up to 30 s polling; unsaved chunks are invisible.

---

## 4. Block colors & biome tinting

- **Base palette is hardcoded in Java**: `core/.../world/Blocks.java` (~1200 `register(new Block(index, "minecraft:...", 0xRRGGBB))` entries) — the color literals are Mojang's block **map colors** (`MapColor`) per block, maintained by hand for each MC update.
- **Curated override palette**: `ColorsConfig` (`colors.yml`) contains a large default `BLOCK_COLORS` map (hand-tuned hex per block, e.g. leaves colors differ from the raw map colors), plus lists that drive block behavior flags: `BLOCKS_GRASS`, `BLOCKS_FOLIAGE`, `BLOCKS_DRY_FOLIAGE`, `BLOCKS_WATER`, `BLOCKS_GLASS`, `BLOCKS_FLAT`, `BLOCKS_AIR`. `Block`'s constructor resolves final color = `ColorsConfig.BLOCK_COLORS.getOrDefault(id, vanillaColor)` and packs the flags into one byte. Black (0x000000) = invisible/skipped.
- **Special-case state-dependent colors** in `Colors.fixBlockColor`: melon/pumpkin stems (by age), wheat (lerp by age), redstone wire (`RedStoneWireBlock.getColorForPower` via platform call), cocoa (by age), farmland (moisture).
- **Biome tinting**: each `Biome` record carries `color` (for biomes renderer), `foliage`, `dryFoliage`, `grass`, `water`, and a `grassModifier` lambda (delegates to vanilla `GrassColorModifier` — swamp/dark-forest handling). On Fabric, `FabricWorld` registers every biome from the dynamic registry: override chain = `colors.yml` per-biome maps → biome's `SpecialEffects` overrides → default computed from clamped temperature/downfall via the **colormap images** (`Colors.getDefaultGrassColor` etc.).
- **Colormaps**: `Colors`' static init reads `web/images/grass.png`, `foliage.png`, `dry_foliage.png` (256×256) — these are **Mojang's colormap textures** shipped in `webmap/public/images/` (extracted into the web dir). Index = `(1-temp)`, `(1-hum*temp)` — same formula as vanilla.
- **Modded blocks on Fabric**: `loadBlocks()` registers every entry of the runtime `Registries.BLOCK` with its `defaultMapColor().col` — so mod blocks get whatever map color the mod author declared (frequently a generic color, or 0 = invisible). Server admins can override per block id in `colors.yml`. There is **no texture scanning** (unlike BlueMap/squaremap texture packs).
- **Index persistence for the web UI**: `BlockRegistry` (cap `MAX_INDEX = 2047`) and per-world `BiomeRegistry` (cap 511) assign stable small integers, persisted as gzip'd JSON (`web/tiles/blocks.gz`, `web/tiles/<world>/biomes.gz`); these indices are packed into the blockinfo binary tiles. The caps are a real limitation for heavily modded servers (GitHub #75).
- `BlockRegistry.register(String,int)` logs a warning "Registering unknown vanilla block" when a `minecraft:` block isn't in the hardcoded `Blocks` list (i.e. the plugin lags behind a MC update).

## 5. Tile storage & web-served files

```
web/                             (extracted from jar; overwritten on start unless read-only)
├── index.html, pl3xmap.js, css, leaflet.js, lang/*.json, images/... (incl. colormaps, icons, skins/)
└── tiles/
    ├── settings.json            (global settings + players, rewritten ~continuously)
    ├── blocks.gz                (block index → id map, gzip json)
    └── <world>/                 (":" → "-")
        ├── settings.json        (per-world: spawn/center/zoom/ui/tileUpdateInterval=10)
        ├── biomes.gz
        ├── markers.json         (layer definitions)
        ├── markers/<layer>.json (marker lists per layer, ":"→"-")
        ├── .rms                 (RegionModifiedState binary)
        └── <zoom>/<renderer>/<x>_<z>.png
        └── <zoom>/blockinfo/<x>_<z>.pl3xmap.gz
```
- JSON writes: serialize to string → write `.tmp` → atomic move (`FileUtil.writeJson`). Tile skip logic: `TileImage.written` flag (nothing drawn → no write); zoomed tiles are read-modify-write merged. `resetmap` command deletes a world's tile tree.
- Player skins: `PlayerTexture` (a raw `Thread` spawned per player join) downloads the skin, crops 2D head and composes an isometric 3D head, writes to `web/images/skins/{2D,3D}/<uuid>.png`.
- **HTTP caching**: `/tiles*` responses get `Cache-Control: max-age=0, must-revalidate, no-cache`; ETag = file mtime (from `PathResourceManager.setETagFunction`); `.gz` files served with `Content-Type: application/json` + `Content-Encoding: gzip`. Missing tiles return **200 with empty body** (custom 404 handler special-cases `/tiles` + `.png`/`.gz`) so Leaflet doesn't render broken-image icons.

## 6. Web server & webmap frontend

### Server (`core/.../httpd/HttpdServer.java`)
- Undertow core, HTTP/2 enabled, single listener `settings.internal-webserver.bind`:`port` (default `0.0.0.0:8080`, can be disabled for external nginx/apache).
- Handlers: a path handler → (a) static `ResourceHandler` over the web dir (`PathResourceManager`, `followLinks` opt-in, default false — symlink traversal is the only explicitly considered security knob; path canonicalization is delegated entirely to Undertow; no auth, no rate limiting, no CORS config); (b) `/sse` (global; `settings` events) and `/sse/{world}` (per-world `markers` events) using Undertow's `ServerSentEventHandler`; sets `X-Accel-Buffering: no` for reverse proxies; unknown world → JSON error listing valid worlds (world names with `:`→`-` matching, added after GitHub #91).
- `LogFilter` (log4j filter) suppresses undertow/xnio/jboss logger output during start/stop.
- Live data flow: `UpdateSettingsData` pushes the whole settings JSON over SSE whenever its hash changes and writes `tiles/settings.json` every ~20th parse; `UpdateLiveData` pushes marker lists per layer over the world SSE when the markers' hashCode changes (guava cache of last hashes, 1 min expiry). Frontend falls back to polling `tiles/settings.json` every 1 s when SSE isn't delivering.

### Frontend (`webmap/`)
- **Leaflet 1.9.4** (vendored `public/leaflet.js`, `externals` in webpack), TypeScript 4.7, webpack 5 + sass + svg-sprite-loader + compression/copy plugins; MIT (package.json). ~60 TS source files; `L.CRS.Simple`-style flat map.
- Key classes: `Pl3xMap.ts` (bootstrap, SSE init, 1 s settings polling loop), `WorldManager`/`World.ts` (per-world settings/biome palette/blockinfo cache, own per-world SSE), `ReversedZoomTileLayer` (tileSize 512, reversed zoom URL, fetch()-based tile loading converting blobs to data-URLs to force revalidation while avoiding `createObjectURL` leak), `DoubleTileLayer` (two stacked tile layers swapped on `load` for flicker-free refresh), `PlayerManager`/`PlayersTab` (player list + markers with rotation, health/armor icons), `ControlManager` + `LinkControl`/`CoordsControl`/`BlockInfoControl` (position-configurable), `SidebarControl` with `WorldsTab`/`PlayersTab`/`LayersTab`/`MarkersTab`, marker classes mirroring the server marker model (Icon/Circle/Ellipse/Polygon/Polyline/Multi*/Rectangle + options Stroke/Fill/Tooltip/Popup; `L.ellipse`/`L.rotated` plugins vendored), `Palette`/`BlockInfo` decoding the binary blockinfo tiles, client-side i18n (`lang/*.json`, translates block/biome keys). URL hash deep-linking (world/renderer/zoom/x/z). Renderer switching per world with per-renderer icons.

## 7. Configuration, commands, permissions, API

- **Config files** (SimpleYAML; reflection over static fields annotated `@Key`/`@Comment` in `AbstractConfig` subclasses): `config.yml` (`Config`: web dir/format/quality, zoom snap/delta/wheel, httpd, live-update threads, render threads, GC toggles, language file), `colors.yml` (`ColorsConfig`), `lang-*.yml` (`Lang`, MiniMessage strings + UI labels; ja/pl/zh shipped), `layers/players.yml`, `layers/spawn.yml`, `layers/worldborder.yml`; per-world overrides under `world-settings.<world>` in config.yml (`WorldConfig`: enabled, renderers-map (renderer→icon), biome-blend, skylight, translucent fluids/glass, heightmap-type, UI positions, display-name, order, center, zoom default/max-out(3)/max-in(2), visible-areas (rectangle/circle/border JSON areas that gate region/chunk/block rendering)).
- **Commands** (cloud v2; root `/map` | `/pl3xmap`, brigadier-native on both platforms): `fullrender`, `radiusrender`, `pauserender`, `resumerender`, `reload`, `resetmap` (requires `confirm` via cloud-processors-confirmation), `hide`/`show` [player], `status` (render progress/CPS/ETA), `stitch` (offline-merge tiles into one PNG), `version`, `help`, `confirm`. Permissions `pl3xmap.command.<name>`; platform parsers abstracted via `PlatformParsers` (cloud-fabric's `columnPos` etc.).
- **API/addon surface**: static `Pl3xMap.api()`; registries (worlds, players, renderers — addons can register custom `Renderer.Builder`s and `Heightmap`s and image `IO.Type`s); **event bus**: `EventRegistry.register(EventListener)` reflects `@EventHandler` methods; each `Event` subclass holds a static `handlers` list; events: `Pl3xMapEnabledEvent`, `Pl3xMapDisabledEvent`, `ServerLoadedEvent`, `WorldLoadedEvent`, `WorldUnloadedEvent` — no priorities/cancellation.
- **Markers API**: per-world `Registry<Layer>`; `Layer` (label supplier, updateInterval, showControls, defaultHidden, priority, zIndex, pane, css, liveUpdate) with `SimpleLayer`/`WorldLayer`; built-ins `PlayersLayer`, `SpawnLayer`, `WorldBorderLayer` (each with own config file); `CustomLayer.load` reads admin-dropped JSON files from `<data>/markers/<world>/*.json`. `Marker` hierarchy (icon/circle/ellipse/polyline/polygon/multi/rect) with `Options` builder (stroke/fill/tooltip/popup), all `JsonSerializable` writing Leaflet-friendly compact arrays. External addon ecosystem (banners/claims/mobs/signs/warps, listed in Modrinth deps).

## 8. Threading model & performance notes

- **Executors** (all `ForkJoinPool` via `Pl3xMap.ThreadFactory`): `Pl3xMap-Renderer` (n = render-threads, default half cores) — the only sized pool doing real work; singletons: `-Processor`, `-DoubleChecker`, `-Progress`, `-Settings`; per world: `-Markers` (1) and `-LiveData` (**default half cores per world** — thread bloat with many worlds); plus a raw `Thread` per skin download; client mod adds `-Update`.
- **Server (main) thread**: only `Scheduler.tick()` per server tick (Fabric `END_SERVER_TICK`; Bukkit `runTaskTimer`, Folia global scheduler). Tasks immediately dispatch to their executors, so main-thread cost is tiny — but marker `getMarkers()` implementations read live server state (players, world border, spawn) **from async threads without synchronization** (Folia crash GitHub #138 is exactly this class of bug). World registration (dir creation, config load, biome registry save) does disk IO on the server thread during world-load events.
- **Scheduling style**: recursive self-rescheduling `CompletableFuture.runAsync` loops with `Thread.sleep(delay)` inside the pool thread (RegionProcessor 5 s, DoubleChecker 30 s, Progress 1 s). Pause = `checkPaused()` busy-wait (50 ms sleeps) called **per block scanned** (~262k calls/region/renderer even when not paused — cheap boolean read, but a non-volatile one).
- **Hot-path allocations**: per column a `BlockData` + `LinkedList` for glass; boxed `Integer` glass colors; lambda-based neighbor sampling per pixel for biome blending; per-region per-renderer `int[262144]`; PNG decode+encode ×(zoom levels 0–3) ×renderers ×regions (the read-modify-write zoom scheme roughly quadruples image IO and serializes neighbors via file locks).
- **Memory characteristics**: region cache ≤100 regions ×1024 chunks with full section arrays; deliberate `System.gc()` calls after renders (and optionally during); the pinned OOM issue (#7) reflects that full renders are memory-hungry by design; static `FILE_LOCKS` maps grow monotonically (one entry per tile path ever written).
- **Data races (real, mostly benign)**: `running`, `paused`, `future` flags in `RegionProcessor`/`AbstractDataTask`/`UpdateSettingsData` are non-volatile booleans checked cross-thread; `RegionProcessor.regionsToScan` uses get-or-default+put instead of `compute`; `ticketsToScan.contains` O(n) → O(n²) queueing during fullrender; `Pl3xMapFabricServer.biomeFeatureCache` is an unsynchronized `LinkedHashMap` mutated from multiple render threads (corruption risk when flowermap renderer is active).
- **Failure→skip bug**: in `RegionProcessor.schedule(...).whenComplete`, the region's `.rms` timestamp is set **even when the scan task failed**, so a region that failed (e.g. one corrupted chunk aborts `Region.loadChunks`, cf. issues #70/#126) is treated as up to date and never retried until the file changes again → persistent holes.
- **Colors.blend alpha bug**: `argb((int) a * 0xFF, ...)` casts the 0..1 double to int **before** multiplying — resulting alpha is 0 unless a==1.0; masked in practice because most final pixels get forced opaque, but it corrupts partially-transparent blends (visible at downsampled edges).

## 9. Error handling, logging, world management

- Style: broad `catch (Throwable t) { Logger.severe(msg, t); }` at task boundaries (scan task, processor loop, layer parsing); many **silent** swallows (`RegionModifiedState` load/save `catch (Throwable ignore)`, `atomicMove` ignores AccessDenied/NoSuchFile, colormap load failure degrades to empty arrays → all default tints black). Exceptions print via `printStackTrace()` rather than the platform logger.
- `Logger` is a static facade formatting MiniMessage through the adventure console audience (falls back to `System.out` before enable); `debug()` gated by `settings.debug-mode`; log4j `LogFilter` mutes undertow/xnio noise.
- Worlds: `WorldRegistry` keyed by dimension id on Fabric (`minecraft:overworld`…) and by Bukkit world name on Bukkit (a naming split that leaks into config keys — `Config.reload()` renames `world` → `minecraft:overworld` keys on non-Bukkit); `World.Type` enum from dimension id; per-world enable flag; unregister on unload fires `WorldUnloadedEvent`. `cloneWorld` exists to re-wrap a level. Dimension names sanitized (`:`→`-`) for paths/URLs/SSE (mismatch here caused GitHub #91).

## 10. Known problems from the issue tracker (granny/Pl3xMap)

Recurring complaints (issue numbers verified against GitHub, state as of 2026-07):
1. **#7** (pinned) — OOM reports funnel here; maintainer position: high RAM use is by design, tune your heap. Constant stream of duplicates.
2. **#94 / #96** — server-side memory leak traced to the bundled **Undertow** (heap dumps show ~42% of heap in its queue handler); #96 closed as dup, #94 open with `bug`.
3. **#115** — browser-side leak: webmap tab grows to 3+ GB when left open (tile refresh churn).
4. **#75** (open) — **hardcoded block/biome palette index caps** too small for modded servers; the key modded-blocks blocker.
5. **#80, #92, #72, #59, #104** — every MC version bump produces `NoSuchField/NoSuchMethod/NoClassDef` startup crashes, worst on Spigot (#80 most-commented); i.e. tight coupling to server internals per version.
6. **#126, #70** — corrupted/Chunky-pregenerated chunks (`ZipException`) break region scans; renders show holes (see §8 failure→skip bug); **#109** black unrendered areas persisting after fullrender.
7. **#62, #47** — rendering artifacts with custom worldgen (Larion, JJThunder) that competing maps don't show.
8. **#1, #61, #52, #3** — tiles/state not updating without restart, fullrender finishing but web not updating, `resetmap` not working, disabled dimensions rendering anyway.
9. **#73, #58, #68, #63, #31** — new-block color gaps after MC updates (pale garden, tuff bricks, copper grates), wrong grass coloring, custom colors not applying.
10. **#13, #10, #91** — webserver friction: nginx reverse-proxy breaks live biome/Y display, privileged-port bind failures, SSE endpoint world-name mismatch.
11. **#138** (open) — **Folia crash**: async `getArmorPoints` (`ArrayIndexOutOfBoundsException`) — async access to live player state.
12. **#142** (open) — privacy: player locations in hidden dimensions still retrievable from the JSON payloads (also #143 player counts, #160 spawn marker in every dimension).
13. **#38** — tile writes to network storage caused main-thread TPS dips; **#67** — skin-fetch request spam.
14. **#6** (open) — CPS/ETA math wrong (minimum CPS 1024); **#144** — marker `zIndex` has no effect; **#156/#65** — zoom-out range and per-dimension center complaints.

## 11. Weaknesses/risks identified in the code (own review)

1. **Polling-based updates** (RegionDoubleChecker stats every region file of every world every 30 s) + reliance on autosave → 30 s..minutes of staleness and constant filesystem churn on big servers; the event-driven `RegionFileWatcher` was abandoned (commented out).
2. **Failed region scans are marked as completed** (§8) → permanent holes until the region file is touched again. Compounded by `Region.loadChunks` aborting the whole region on a single bad chunk.
3. **Read-modify-write zoom tiles**: each region task decodes/encodes up to 4 PNGs per renderer; concurrent regions sharing a zoomed tile serialize on a static per-path lock map that **leaks lock entries forever**; crash mid-write mitigated by tmp+atomic move but torn logical state across zoom levels is possible.
4. **Unbounded queues**: `ticketsToScan` deque unbounded with O(n) dedupe; marker/live JSON strings built in full in memory per tick-ish cadence.
5. **`UpdateSettingsData` runs every scheduler tick** (delay=1) serializing the full settings+players JSON, hashing to decide SSE pushes, and writing `settings.json` every 20th pass — busy work scaling with player/world count.
6. **Non-volatile cross-thread flags** (`paused`, `running`), unsynchronized `biomeFeatureCache` (Fabric flowermap), unsynchronized async reads of live server state in layers (Folia-fatal, #138).
7. **`checkPaused()` per block** — a shared-singleton busy-wait embedded in the innermost loop; pausing blocks render threads in sleep loops rather than suspending tasks.
8. **Colors.blend alpha-cast bug** (§8) and downsampling averaging transparent pixels → edge artifacts on zoomed-out tiles.
9. **Skin fetch = 1 raw Thread + network IO per join**, no dedupe/backoff (issue #67); metrics/manifest reads on startup are fine but jar re-opened for extraction.
10. **Palette index caps** (2047 blocks / 511 biomes) baked into the binary blockinfo format — breaks heavily modded servers (#75).
11. **Security posture is minimal**: no auth on SSE/static; hidden-player filtering happens at serialization but world-presence leaks existed (#142); `settings.json` includes all worlds; web dir extraction overwrites customizations unless read-only flag set.
12. **No tests of any kind** (no unit/integration test sources in any module) — regressions ship until users report them; per-MC-version breakage (#80 family) confirms this.
13. **`System.gc()` calls** as memory management (default on after renders) — symptom of unbounded allocation during renders rather than pooling/streaming.
14. **Two half-core pools** (render + per-world live-update) can oversubscribe CPUs on multi-world servers.

## 12. Comparison-matrix input

| Aspekt | Umsetzung in Pl3xMap | mögliche Schwäche |
|---|---|---|
| Modulstruktur | Gradle-Multiprojekt core/bukkit/fabric/webmap; Uber-Jar für alle Plattformen durch manuelles Jar-Merging im Root-`jar`-Task | „Janky“ Merge-Logik (zipTree/`.jar.tmp`), fragil bei Gradle/Loom-Updates; Forge-Modul verwaist |
| Fabric-Integration | Entrypoints client+server, nur 2 Mixins (Player-NBT-Flag, In-Game-Map-Textur), Access Widener, Fabric-API-Lifecycle-Events, cloud-fabric, jar-in-jar | Java-25-Zwang; Client-Mixin greift tief in Renderer-Interna; pro MC-Version anfällig |
| Rendering | Eigener MCA-Parser (BlueNBT, BlueMap-abgeleitet), Region=Tile 512², Spiral-Reihenfolge, mehrere Renderer pro Scan-Durchlauf, Heightmap-/Licht-/Fluid-/Glas-Shading | Nur gespeicherte Chunks sichtbar; ein korrupter Chunk verwirft die Region; Fehler-Region wird als „fertig“ markiert (Löcher) |
| Blockfarben (Modded) | Zur Laufzeit aus Block-Registry via `defaultMapColor()`, Overrides in colors.yml; keine Texturanalyse | Mod-Blöcke oft grau/unsichtbar; Index-Limit 2047 Blöcke/511 Biome (#75); Farbliste pro MC-Update manuell zu pflegen |
| Tile-Speicherung | `tiles/<welt>/<zoom>/<renderer>/<x>_<z>.png`, tmp+atomic move, `written`-Flag verhindert Leerschreiben | Zoom-Tiles = Read-Modify-Write existierender PNGs; statische Lock-Map wächst unbegrenzt |
| Zoom-Erzeugung | Zoom 0..3 sofort im selben Region-Task per Box-Average-Downsampling in geteilte Dateien; Zoom-in nur clientseitig gestreckt | 4× Encode/Decode pro Renderer/Region; Kontention auf geteilten Tiles; Downsampling mittelt Transparenz ein (Randartefakte) |
| Update-Trigger | mtime-Polling aller Regionsdateien (30 s) + `.rms`-Zeitstempel; keine Block-/Chunk-Events; FileWatcher deaktiviert | Latenz an Autosave gebunden; Dauer-Stat-Aufrufe; verpasste Änderungen während laufender Scans |
| Scheduling/Threading | Viele kleine FJP-Executor; Renderer-Pool (½ Kerne); Tick-Scheduler auf Serverthread delegiert nur | Per-Block-Busy-Wait `checkPaused`; nicht-volatile Flags; pro Welt ½-Kern-LiveData-Pool; async Zugriff auf Live-Serverstate (Folia-Crash #138) |
| Caching | Caffeine-Regioncache (100, 1 min); Marker-Hash-Cache; Client: Data-URL-Fetch statt Browser-Cache | Kein Tile-Cache serverseitig; settings.json quasi jede Sekunde neu erzeugt; Undertow-Leak (#94) |
| Webserver | Undertow (relokiert), HTTP/2, statisch + 2 SSE-Endpunkte, ETag=mtime, no-cache für Tiles, 200-leer statt 404 für Tiles | Kein Auth/CORS/Rate-Limit; Reverse-Proxy-Probleme (SSE, #13/#91); Privacy-Leaks in JSON (#142) |
| Frontend | Leaflet 1.9.4 + TypeScript, Doppel-Tilelayer für flackerfreien Refresh, SSE + 1s-Polling-Fallback, Sidebar/Layer/Marker/Blockinfo/i18n | Browser-Speicherleck bei Dauerbetrieb (#115); 1s-Polling; Leaflet-Zoom-Workarounds hartkodiert |
| Konfiguration | SimpleYAML + Reflection über annotierte statische Felder; config/colors/lang/layers + per-Welt-Overrides | Statische Mutable-Globals; kein Reload-Schutz; Weltnamen-Schema Bukkit vs. Fabric inkonsistent |
| Befehle | cloud v2 mit Brigadier, `/map`-Subcommands, Confirmation-Processor, Permissions `pl3xmap.command.*` | Ausführung via `CompletableFuture.runAsync` auf Common-Pool; wenig Feedback bei Fehlern |
| API | Singleton `Pl3xMap.api()`, Registries, Marker-/Layer-API, Custom-Renderer/Heightmaps, JSON-Custom-Layer, Event-Bus | Event-Bus reflektionsbasiert, statische Handler-Listen, keine Prioritäten/Cancel; API == Interna (keine Stabilitätsgrenze) |
| Fehlerbehandlung | Catch-Throwable an Task-Grenzen, Log-and-Continue | Viele stille Swallows; `printStackTrace`; Fehlregionen als erledigt markiert |
| Logging | MiniMessage-Logger über Adventure-Konsole, Debug-Flag, log4j-Filter für Undertow-Spam | Kein Level-/Logger-Framework-Durchgriff; Debug sehr spammy |
| Tests | Keine (kein einziges Testmodul/-verzeichnis) | Regressionsrisiko bei jedem MC-Update (belegt durch #80/#92/#104) |

## 13. Licensing & what must NOT be copied

- **Pl3xMap license**: MIT (root `LICENSE`, © 2020–2023 William Blake Galbreath). **Webmap**: same repo, `package.json` declares MIT. Fabric `fabric.mod.json` declares MIT.
- **BlueMap-derived files** (MIT, © Lukas Rieger/Blue): `MCAMath`, `PackedIntArrayAccess`, `BlockStateDeserializer`, `ChunkLoader`, `Chunk_1_13/_1_15/_1_16/_1_18`, `RegionModifiedState` — copying them requires keeping BlueMap's MIT notice; for clean-room work, re-derive from format documentation instead.
- **`core/.../world/BiomeManager.java` is decompiled Mojang code** (header: "Borrowed from Mojang in good faith with much love <3"). **Not MIT, not redistributable under Pl3xMap's license terms — must NOT be copied.** Reimplement the biome-fuzz (zoomed voronoi) from public format documentation or accept 4×4 blocky biome sampling.
- **Mojang assets**: `webmap/public/images/grass.png`, `foliage.png`, `dry_foliage.png` are the vanilla colormap textures — Mojang-owned; **do not ship copies**. Alternatives: extract at runtime from the server/client jar on the user's machine, or compute per-biome colors from registry data.
- Block color literals in `Blocks.java` are Mojang `MapColor` values (facts/data — low risk to re-derive from the runtime registry as the Fabric path already does); the curated `ColorsConfig` palette is Pl3xMap's own creative work (MIT, but re-derive independently for clean-room hygiene).
- FlowerMap logic credited to `Draradech/FlowerMap` (CC0) — free to reuse.
- The pattern "fabric mergeShadowAndJarJar" is copied from BlueMap's build (MIT, link in `fabric/build.gradle.kts`).
