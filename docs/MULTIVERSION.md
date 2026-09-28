# Multi-version support (0.5.2)

Minecraft 26.3, Fabric Loader 0.19.5 and the NeoForge target lines were checked
against the official Minecraft, Fabric and NeoForge release feeds in September
2026. Newest stable Minecraft: **26.3**.

## Download table

| Minecraft | Fabric/Quilt artifact (`explorersfriend-fabric-…-0.5.2.jar`) | NeoForge artifact (`explorersfriend-neoforge-…-0.5.2.jar`) | Java |
| --- | --- | --- | ---: |
| 1.21.1 | `1.21.1` | `1.21.1` | 21 |
| 1.21.2 | `1.21.2-1.21.4` | `1.21.2` | 21 |
| 1.21.3 | `1.21.2-1.21.4` | `1.21.3` | 21 |
| 1.21.4 | `1.21.2-1.21.4` | `1.21.4` | 21 |
| 1.21.5–1.21.8 | `1.21.5-1.21.8` | exact version (`1.21.5`, …, `1.21.8`) | 21 |
| 1.21.9–1.21.10 | `1.21.9-1.21.10` | exact version (`1.21.9` or `1.21.10`) | 21 |
| 1.21.11 | `1.21.11` | `1.21.11` | 21 |
| 26.1–26.1.2 | `26.1` | `26.1` | 25 |
| 26.2 | `26.2` | `26.2` | 25 |
| 26.3 | `26.3` | `26.3` | 25 |

Prefix the table value with the platform artifact name shown in the column
heading. Install exactly **one** variant in `mods/`. Fabric API is required for
Fabric/Quilt; NeoForge needs no extra library. Clients still need nothing.
Minecraft 26.3 on Fabric needs Fabric Loader 0.19.5 or newer. Other minimums
are encoded in each jar's loader metadata.

## Family boundaries (compile-verified, not guessed)

Discovered by compiling the identical platform source against every stable release:

- **1.21.2**: `DynamicRegistryManager.get → getOrThrow`, `teleport` gained
  PositionFlags — splits from 1.21.1.
- **1.21.5**: `DyeColor.getName` removed (adapters use `asString()`, valid 1.21.2+),
  and — invisible to compile probes — `WorldChunk.setBlockState`'s third parameter
  changed from `boolean` to `int` flags. Mixin targets are validated at *runtime*,
  so this boundary was found by the per-version dedicated-server smoke tests and
  splits 1.21.2–1.21.8 into two jars (1.21.2–1.21.4 / 1.21.5–1.21.8).
- **1.21.9**: authlib `GameProfile` became record-style (`name()`/`properties()`),
  `Entity.getWorld → getEntityWorld`, spawn moved to
  `LevelProperties.getSpawnPoint()`.
- **1.21.11**: numeric permission levels replaced by `PermissionPredicate`
  (shimmed in `command/Perms`), `BiomeEffects` became a record.
- **26.1 (era boundary)**: Minecraft unobfuscated, official names
  (`ServerLevel`, `LevelChunk`, `Identifier`, …), Java 25, loom's
  `net.fabricmc.fabric-loom` no-remap plugin, access widener namespace `official`,
  CHUNK_LOAD event gained a `newlyGenerated` flag, `PermissionSet` system.

## Architecture

- `common/` — 69 Minecraft-free classes (renderer, web server + UI, caches, color
  analysis, overlay/claim/marker/player models, region parser): one source, Java 21
  bytecode, bundled into every artifact. No `net.minecraft` import (enforced by its
  own compile classpath).
- `platforms/fabric-*` — thin adapters (~22 classes each): entrypoint, world/registry
  access, mixin, commands, claim providers. Per-family copies whose differences are
  exactly the API breaks listed above; common behaviour is guarded by the shared
  core + its 90+ unit tests.
- `buildSrc` — two convention plugins: `explorersfriend.platform-yarn`
  (legacy loom, yarn mappings, remapJar) and `explorersfriend.platform-noremap`
  (26.x pipeline, jar is final). Per-module pins live in each module's
  `gradle.properties`; nothing dynamic.
- `platforms/neoforge-*` — one official-mappings module per 1.21.x release,
  plus 26.1, 26.2 and 26.3 modules. NeoForge's MC-specific loader lines and
  dependency ranges prevent one untested jar from advertising other releases.

## Build commands

```bash
./gradlew buildAllVersions      # every artifact + core tests
./gradlew testAllVersions
./gradlew packageAllVersions    # dist/ + checksums + release-manifest.json
./gradlew verifyAllArtifacts    # structural jar checks
./gradlew :platforms:fabric-26.3:build   # single target
./gradlew :platforms:neoforge-1.21.10:build
python scripts/smoke.py --module fabric-26.3 --mc 26.3 --java 25 \
    --loader 0.19.5 --jar dist/explorersfriend-fabric-26.3-0.5.2.jar
```

The Gradle JVM must be Java 25 (loom requires it for 26.x targets); per-module
toolchains still emit Java-21 bytecode for the 1.21.x artifacts (checked via
`options.release`).

## Claim provider availability per target

| Target | FTB Chunks | Open Parties and Claims | Common Protection API | Waystones layer | JSON import |
| --- | --- | --- | --- |
| 1.21.1 | ✅ adapter | ✅ adapter | ✅ (0.4.0) | ✅ (0.4.0) | ✅ |
| 1.21.2–1.21.8 (both jars) | – no Fabric build exists | ✅ (no OPAC release for 1.21.2/1.21.7 themselves) | ✅ (0.4.0) | ✅ (0.4.0) | ✅ |
| 1.21.9–1.21.10 | – no Fabric build exists | ✅ | ✅ (0.4.0) | ✅ (0.4.0) | ✅ |
| 1.21.11 | ✅ adapter (0.4.0) | ✅ | ✅ (0.4.0) | ✅ (0.4.0) | ✅ |
| 26.1/26.2 | – no Fabric build exists | ✅ adapter (0.4.0) | ✅ (0.4.0) | ✅ (0.4.0) | ✅ |
| 26.3 Fabric | – | – (no validated API yet) | adapter included; runtime smoke pending | – (no validated API yet) | ✅ |

On new NeoForge targets (1.21.2–1.21.11, 26.1, 26.3), only the JSON claim
import is currently enabled. Existing NeoForge 1.21.1 and 26.2 integrations
remain available. These are platform-specific differences; the table above
describes Fabric/Quilt.

A missing external claim mod is never an error: detection logs it and the base map
is fully functional everywhere.

## Cache & data compatibility across versions

Shareable: markers (`markers.json`), manual colors, config, tiles **only if** the
render algorithm and resource hashes match. Version-bound and self-invalidating:
block-color cache (keyed by jar-set hash + algorithm + animation mode — a Minecraft
version change changes the vanilla jar hash and forces a clean re-scan), texture
cache (content-addressed, safely reusable), rendered-chunks index (harmless to lose).
Corrupt/incompatible caches quarantine and rebuild — no migration ever blocks a start.

Upgrade procedure: backup server → stop → update Minecraft/Fabric → swap in the
matching mod JAR (remove the old one) → start → watch the `[ExplorersFriend/…]`
scan/cache lines → spot-check map + overlays.

## Adding a future Minecraft version

1. Check piston-meta (Java version) + fabric meta (loader/yarn) + Modrinth (API).
2. Try the nearest family first: point `scripts`' probe at its source with the new
   pins; a clean compile + smoke test ⇒ extend that family's `supportedMc` + range.
3. Otherwise copy the nearest family into `platforms/fabric-<ver>`, fix compile
   errors (javap against the mapped jar in `.gradle/loom-cache` answers renames),
   update `fabric.mod.json`, add the module to CI, run smoke, update this matrix.
