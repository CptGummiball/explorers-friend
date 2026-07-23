# Multi-platform feasibility analysis and architecture decision

Sources verified 2026-07-19 against meta.quiltmc.org, maven.neoforged.net,
files.minecraftforge.net, fill.papermc.io, maven.ftb.dev and Modrinth.
Status: **implemented and runtime-verified** — every "tested" entry below names
a real dedicated-server smoke run (dist/test-results.json).

## 0. Verified support matrix (0.4.x multi-platform build)

| Platform | Artifacts | Runtime-verified on | Result |
| --- | --- | --- | --- |
| Fabric | 7 family jars (1.21.1…26.2) | all 7 targets (0.4.3 release smoke) | ✅ |
| Quilt | same 7 Fabric jars | Quilt Loader 0.30 on 1.21.1 and 26.2 (self-identifies as `quilt`) | ✅ |
| NeoForge | `neoforge-1.21.1`, `neoforge-26.2` | NeoForge 21.1.243 and 26.2.0.32-beta incl. full render to tiles | ✅ |
| Spigot | one `spigot-paper` jar (all MC versions) | BuildTools Spigot 1.21.1 (17/17) | ✅ |
| Paper | same jar | Paper 1.21.1 (17/17, with GriefPrevention) and Paper 26.1.2 | ✅ |
| Forge | — | — | deferred (see §3a) |

Cross-platform negative tests 3/3: a NeoForge or Spigot jar in a Fabric
`mods/`, or a Fabric jar on NeoForge, is cleanly ignored with the server
staying healthy — wrong artifacts never half-load.

Findings fixed during verification (both found by cross-platform smokes):
- 26.x console/RCON permissions: the level-based shim denied ALL_PERMISSIONS
  holders (affects the released 0.4.3 fabric 26.x jars; fixed for the next release).
- MC 26.x moved world storage to `dimensions/<ns>/<path>/region` (all backends
  handle both layouts now).

Known limitation: MC 26.x removed the worldgen JSONs from the server/client
jars, so **Bukkit 26.x runs with default biome tints** (grass/foliage/water
uniform); Fabric/Quilt/NeoForge read tints from the registry and are unaffected.

## 1. Platform availability (verified, not guessed)

| Platform | Coverage of our 15 target MC versions | Evidence |
| --- | --- | --- |
| Fabric | all (shipped since 0.3.0) | existing artifacts |
| Quilt | **all** — loader 0.30.x lists every stable version incl. 26.2 | meta.quiltmc.org |
| NeoForge | **all** — 21.1.243 (MC 1.21.1) … 26.2 line | maven.neoforged.net |
| Forge | all except 1.21.2 (skipped upstream); 52.1.16 (1.21.1) … 65.0.9 (26.2) | files.minecraftforge.net promotions |
| Paper | all — 1.21.1…1.21.11, 26.1.x, 26.2 | fill.papermc.io v3 |
| Spigot | via BuildTools per stable version (no download API; test coverage: Paper everywhere + BuildTools-Spigot on anchor versions) | spigotmc.org |

## 2. Technical comparison matrix

| Bereich | Fabric | Quilt | NeoForge | Forge | Spigot/Paper |
| --- | --- | --- | --- | --- | --- |
| Lifecycle | `ModInitializer` + lifecycle events | same (loads Fabric mods natively) | `@Mod` constructor + mod-bus events | `@Mod` + FML events (diverged from NeoForge) | `JavaPlugin` onEnable/onDisable |
| Events | Fabric API callbacks | Fabric API via QFAPI/QSL | NeoForge event bus (`ServerTickEvent`, `ChunkEvent.Load`, …) | Forge event bus (same concept, different packages) | Bukkit `Listener` + `EventHandler` |
| Commands | Brigadier via Fabric API | same | Brigadier via `RegisterCommandsEvent` | Brigadier via `RegisterCommandsEvent` | Bukkit `PluginCommand` (+ Brigadier only via Paper API) |
| Permissions | fabric-permissions-api optional, OP-level fallback | same | NeoForge has none standard → OP-level (+ LuckPerms platform detection) | same | **Bukkit permission system native** (plugin.yml permissions) |
| World access | ServerWorld/ServerLevel direct | same | ServerLevel direct | ServerLevel direct | Bukkit World + **thread-safe ChunkSnapshot** |
| Resource access | mod JARs via loader, client-jar download for textures | same | mod JARs via ModList | same | **no mod jars**; vanilla client-jar download (already our texture source!) + plugin jars + server resource pack |
| Mappings at runtime | intermediary (≤1.21.11), official (26.x) | same as Fabric | **official (mojmap)** all versions | official (mojmap) since 1.20.6 era | CraftBukkit internals (irrelevant — Bukkit API only) |
| Mixins / AW / AT | Mixin + AccessWidener | same | Mixin supported + AccessTransformer | Mixin supported + AT | **none** (no mixins on Bukkit) |
| Java | 21 (≤1.21.11) / 25 (26.x) | same | same | same | same (per MC version) |
| Web server / threading | our own (platform-free) | same | same | same | same; Bukkit main-thread rules stricter (world access only on main thread; ChunkSnapshot solves it) |
| Universal-JAR feasibility | — | **yes, with Fabric jar** | no (see §3) | no (see §3) | no (see §3) |

## 3. Universal-JAR assessment (decision: NO universal jar)

A single jar per MC version for all five platforms fails the project's own
acceptance criteria:

1. **Mappings split (hard blocker for ≤1.21.11)**: Fabric runtime uses
   intermediary names, NeoForge/Forge run official (mojang) names. The same
   compiled adapter bytecode cannot reference `net.minecraft.*` under both
   namespaces — a universal jar would need two full copies of every platform
   adapter class plus loader-dependent selection. That is exactly the "fragile
   classloader hack" category the requirements exclude.
2. **Annotation scanning**: Forge/NeoForge scan every class in the jar for
   `@Mod`; Fabric/Quilt bootstrap classes with unresolvable Fabric-API
   references inside a Forge jar are at minimum fragile across loader updates.
3. **26.x exception exists but is not worth it (yet)**: from 26.1 every loader
   runs official names, so a combined Fabric+NeoForge+Forge jar becomes
   *technically* conceivable (multiple metadata files + separate bootstraps).
   We deliberately do not ship it in the first multi-platform release: the test
   surface triples per artifact and one loader regression would block the whole
   jar. Documented as a possible future optimization.
4. **Spigot**: Bukkit only loads the `plugin.yml` main class, so bundling is
   metadata-safe — but the Spigot backend shares no Minecraft-facing code with
   the loaders anyway (Bukkit API), so bundling buys nothing but risk.

### 3a. Forge decision

LexForge remains alive (builds through 26.2), but shipping it would mean a third
full loader adapter tree with its own Gradle toolchain (FG), its own event/API
surface diverging from NeoForge, and its own test matrix — while the 1.21+
modded-server ecosystem has largely consolidated on NeoForge. Per the project
rule that stability and maintainability outrank artifact count, **Forge is
deferred**: not shipped, documented here, with the NeoForge modules as the
direct porting template if demand materializes. This is the honest support
decision for the platform, not an omission.

**Chosen grouping (option 2 of the preference order — highest level that is
honestly testable):**

```
explorersfriend-fabric-<mc-range>-X.Y.Z.jar     <- one artifact for Fabric AND Quilt
explorersfriend-neoforge-<mc-range>-X.Y.Z.jar
explorersfriend-forge-<mc-range>-X.Y.Z.jar
explorersfriend-spigot-paper-X.Y.Z.jar          <- ONE jar for all MC versions
```

Rationale:
- **Fabric+Quilt**: Quilt loads Fabric mods natively; the wider ecosystem ships
  exactly this way (e.g. OPAC tags its fabric builds `['fabric','quilt']`).
  Each family jar is verified on a real Quilt server before the combination is
  documented (Phase E).
- **NeoForge vs Forge separate**: shared ancestry but diverged event buses,
  packages and metadata (`neoforge.mods.toml` vs `mods.toml`); a compat layer
  would be the kind of instability the requirements forbid.
- **Spigot/Paper single jar across MC versions**: the plugin uses only the
  version-stable Bukkit API (`api-version: 1.21`); rendering does NOT touch
  NMS — full renders use our own Anvil/region parser (file format, platform
  independent) and live updates use thread-safe `ChunkSnapshot`s. Paper is
  treated as Spigot-compatible; Paper-only APIs may be used behind runtime
  detection with a Spigot fallback.

## 4. Architecture: common core + platform adapters

The common core (renderer, tiles, web server + UI, caches, color pipeline,
overlay/claim/marker/player models, config, metrics, spatial index, our own
region parser) is already Minecraft-free and stays the single shared module.
The platform seam is formalized as small interfaces in
`common/.../platform/` (`ServerPlatform`, lifecycle/world/player/command/
permission/resource access) — platform modules construct immutable snapshots
(`ChunkSnap`, player/marker/waystone points) and hand them to the core; no
Minecraft, Bukkit or loader object ever crosses into `common`. No reflection in
hot paths, no per-tick platform checks (adapters are bound once at startup).

```
common/                         (unchanged, MC-free)
platforms/
  fabric-*                      (existing 7 family modules, also serve Quilt)
  neoforge-<family>             (mojmap, ModDevGradle)
  forge-<family>                (mojmap, ForgeGradle/MDG-legacy)
  spigot/                       (single module, spigot-api compileOnly)
```

Families for NeoForge/Forge are derived the proven way: compile probes against
every version + smoke tests (expected boundaries differ from yarn-era ones;
e.g. the 1.21.5 `setBlockState` int-flags change is mappings-independent and
will split families on all loaders).

## 5. Block colors per platform

| Source | Fabric/Quilt | NeoForge/Forge | Spigot/Paper |
| --- | --- | --- | --- |
| Vanilla textures | client-jar download (SHA-verified, existing) | same | same (identical pipeline) |
| Mod jars (blockstates/models/textures) | loader mod list (existing) | ModList jars | n/a — no mod jars exist; documented |
| Custom content | — | — | plugin jars scan + (planned, evaluated) ItemsAdder/Oraxen/Nexo via their resource packs; server resource pack URL/hash |
| Manual overrides | existing `block-colors.jsonc` everywhere | same | same |

## 6. Integration matrix (plan; status per tested build in VERIFICATION.md)

| Integration | Fabric | Quilt | NeoForge | Forge | Spigot/Paper |
| --- | --- | --- | --- | --- | --- |
| FTB Chunks | supported (1.21.1, 1.21.11) | same jar | **supported (1.21.1, live-verified)** | deferred with Forge | platform incompatible |
| Open Parties and Claims | supported (all) | same jar (upstream tags quilt) | **supported (1.21.1 + 26.2, live-verified)** | deferred with Forge | platform incompatible |
| GriefPrevention | platform incompatible | platform incompatible | platform incompatible | platform incompatible | **planned: supported** (16.18.7, Spigot/Paper/Purpur, ≤26.1.2; 26.2 upstream pending) |
| Common Protection API (GOML …) | supported | same jar | unavailable (Fabric-API based) | unavailable | platform incompatible |
| Waystones layer | supported (all) | same jar | **supported (1.21.1 + 26.2, live-verified)** | deferred with Forge | unavailable (mod content) |
| Permissions | fabric-permissions-api → LuckPerms | same | OP-fallback (+ evaluation) | OP-fallback (+ evaluation) | **Bukkit permissions native** |
| Vanish | via PlayerVisibilityProvider API | same | same API | same API | Bukkit vanish metadata (planned) |

## 7. Test obligations (Phase F)

Every documented combination gets a real dedicated-server smoke run (start,
scan, web, API, markers, players, claims detection, clean shutdown, restart
cache hits) — Fabric artifacts additionally on Quilt, Spigot artifact on Paper
(all versions) and BuildTools-Spigot (anchor versions). Negative tests: wrong
platform (Fabric jar on NeoForge server, Spigot jar on Fabric, …), wrong MC
version, missing loader deps, missing optional integrations, both loader
variants at once. CI matrix follows the artifact matrix; a combination may only
be documented as supported after its smoke test passed.

## 8. Data compatibility across platforms

Markers, banner icons, claim colors, manual colors, config, tiles and the
texture/skin caches use namespaced ids, UUIDs and dimension keys — no numeric
platform ids anywhere. A migration Fabric→Paper (or any direction) keeps:
markers.json, config, manual colors, tiles (same renderer), texture cache.
The block-color cache is keyed by jar-set hash and rebuilds automatically when
the jar set differs (e.g. no mod jars on Spigot). Verified rules land in
MULTIVERSION.md/MIGRATION docs in Phase F.
