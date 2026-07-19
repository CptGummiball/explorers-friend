# Building & development

## Requirements

- JDK 21 (Temurin/Oracle/etc.)
- Nothing else — the Gradle wrapper fetches Gradle 9.5.0 and all dependencies.

## Build

```bash
./gradlew build          # compiles, runs all tests, validates the access widener,
                         # produces build/libs/explorersfriend-<version>.jar
./gradlew test           # tests only
./gradlew runServer      # Loom dev server (accept eula in run/eula.txt first)
```

The release JAR is `build/libs/explorersfriend-<version>.jar` (the `-sources` JAR is
for IDEs, not for the mods folder).

## Project layout (single Gradle module, packages as boundaries)

| Package | Contents | Minecraft classes? |
| --- | --- | --- |
| `net.explorersfriend` | entry point | Fabric API |
| `.api` | public API | no |
| `.config` | JSONC config load/validate | no |
| `.util` | atomic files, hashing, color math, jsonc, logging | no |
| `.scan` | JAR inventory + cache | Fabric Loader |
| `.resource` | asset sources (zip/dir/pool), vanilla asset locator | no |
| `.color` | model resolver, texture sampler, color caches, overrides | no |
| `.region` | own Anvil/NBT parser, region chunk extractor | no |
| `.render` | tile renderer, zoom pyramid, tile store, scheduler, full render | no |
| `.world` | server-thread adapters: snapshots, dirty tracking, registries, players | yes |
| `.web` | embedded HTTP server | no |
| `.command` | `/efmap` Brigadier tree | yes |
| `.core` | MapService wiring + startup pipeline | yes |
| `.mixin` | the single mixin (block-change hook) | yes |

Everything without Minecraft classes is covered by plain JUnit tests
(`src/test/java`), including synthetic region files, hostile-zip fixtures and a live
HTTP server on an ephemeral port.

## Conventions

- Java 21, `-Xlint:deprecation,unchecked`, UTF-8.
- Every persistent format carries a `schemaVersion`; color data additionally an
  `algorithmVersion` (`ColorExtractor.ALGORITHM_VERSION`) — bump it whenever sampling
  or resolution logic changes, caches then rebuild themselves.
- All cache writes go through `MoreFiles.writeAtomic`.
- Thread-safety notes live in the class javadoc of every concurrent component.
- One mixin only (`WorldChunkMixin`); prefer Fabric API events for anything new.

## Release checklist

1. `./gradlew clean build` green.
2. Bump `mod_version` in `gradle.properties`, update `CHANGELOG.md`.
3. Smoke test: dedicated server first start (scan), second start (cache hits),
   `/efmap render`, browser check.
