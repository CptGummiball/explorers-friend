# Building & development

## Requirements

- JDK 25 for the Gradle JVM (Loom requires it for the 26.x targets). The `common`
  core and the 1.21.x platform modules still emit Java-21 bytecode via per-module
  toolchains (`options.release`).
- Nothing else — the Gradle wrapper fetches Gradle 9.5.0 and all dependencies.

## Build

```bash
./gradlew buildAllVersions       # all seven artifacts + core tests
./gradlew testAllVersions        # tests only
./gradlew packageAllVersions     # dist/ with jars, checksums-sha256.txt,
                                 # release-manifest.json, compatibility.json
./gradlew verifyAllArtifacts     # structural checks on the dist/ jars

# single version module:
./gradlew :platforms:fabric-1.21.1:build
./gradlew :platforms:fabric-1.21.1:runServer   # Loom dev server
                                               # (accept eula in run/eula.txt first)
```

The release JARs are produced by `packageAllVersions` under `dist/` as
`explorersfriend-fabric-<mc-range>-<version>.jar` (a `-sources` JAR is for IDEs, not
for the mods folder).

## Project layout (Gradle multi-project)

- **`common/`** — the Minecraft-free core: renderer, web server + UI, caches, color
  analysis, overlay/claim/marker/player models, region parser. Compiled once as
  Java-21 bytecode and bundled into every artifact; no `net.minecraft` import
  (enforced by its own compile classpath).
- **`platforms/fabric-<range>/`** — thin per-version-family adapters: entrypoint,
  world/registry access, the mixin, commands, claim providers. One module per
  release artifact (seven, from `fabric-1.21.1` to `fabric-26.2`).
- **`buildSrc/`** — two convention plugins: `explorersfriend.platform-yarn`
  (yarn mappings + remapJar, the 1.21.x modules) and
  `explorersfriend.platform-noremap` (26.x official-names pipeline, jar is final).
  Per-module version pins live in each module's `gradle.properties`.

To add support for a new Minecraft version, follow the "Adding a future Minecraft
version" workflow in [MULTIVERSION.md](MULTIVERSION.md).

### Packages as boundaries (`common/` unless marked as platform)

| Package | Contents | Minecraft classes? |
| --- | --- | --- |
| `net.explorersfriend` | entry point (platform modules) | Fabric API |
| `.api` | public API | no |
| `.config` | JSONC config load/validate | no |
| `.util` | atomic files, hashing, color math, jsonc, logging | no |
| `.scan` | JAR inventory + cache | Fabric Loader |
| `.resource` | asset sources (zip/dir/pool), vanilla asset locator | no |
| `.color` | model resolver, texture sampler, color caches, overrides | no |
| `.region` | own Anvil/NBT parser, region chunk extractor | no |
| `.render` | tile renderer, zoom pyramid, tile store, scheduler, full render | no |
| `.overlay` / `.claims` / `.marker` / `.player` | overlay models + layer logic | no (adapters in platform modules) |
| `.world` | server-thread adapters: snapshots, registries, players (dirty tracking in `common`) | platform modules |
| `.web` | embedded HTTP server | no |
| `.command` | `/efmap` Brigadier tree | platform modules |
| `.core` | MapService wiring + startup pipeline | platform modules |
| `.mixin` | the single mixin (block-change hook) | platform modules |

Everything without Minecraft classes is covered by plain JUnit tests
(`common/src/test/java`), including synthetic region files, hostile-zip fixtures and
a live HTTP server on an ephemeral port.

## Conventions

- Java 21 bytecode for `common` and the 1.21.x modules, Java 25 for the 26.x
  modules; `-Xlint:deprecation,unchecked`, UTF-8.
- Every persistent format carries a `schemaVersion`; color data additionally an
  `algorithmVersion` (`ColorExtractor.ALGORITHM_VERSION`) — bump it whenever sampling
  or resolution logic changes, caches then rebuild themselves.
- All cache writes go through `MoreFiles.writeAtomic`.
- Thread-safety notes live in the class javadoc of every concurrent component.
- One mixin per platform module (the block-change chunk hook — `WorldChunk` on
  1.21.x, `LevelChunk` on 26.x); prefer Fabric API events for anything new.

## Release checklist

1. Bump `mod_version` in `gradle.properties`, update `CHANGELOG.md`.
2. `./gradlew clean packageAllVersions verifyAllArtifacts` green (Gradle JVM =
   Java 25). `dist/` must contain all seven jars plus `checksums-sha256.txt`,
   `release-manifest.json` and `compatibility.json`.
3. Smoke-test each artifact on a dedicated server:
   `python scripts/smoke.py --module <module> --mc <version> --java <21|25>
   --loader <loader> --jar dist/<jar>` (first start scans, second start reports
   cache hits; `/efmap render`; browser check). Run the negative tests:
   `python scripts/negative_smoke.py`.
4. Upload all seven jars from `dist/`. On Modrinth, set each jar's game versions to
   exactly the Minecraft range in its file name.

> **Versioning since 2026-07-19:** build numbers come from the X.Y.Z
> build-attempt scheme - never edit `mod_version` by hand; use
> `python scripts/efver.py` (claim/build/bump). Full rules: [VERSIONING.md](VERSIONING.md).
