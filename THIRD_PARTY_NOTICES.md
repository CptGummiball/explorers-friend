# Third-Party Notices

The Explorer's Friend is an original implementation. It does not embed third-party
library code in its release JAR. The following third-party components are used at
build time or interacted with at runtime:

## Build / runtime platform (not bundled)

- **Minecraft** — © Mojang AB. Proprietary. Required at runtime; never redistributed.
  On dedicated servers the mod can optionally download the official vanilla client
  JAR from Mojang's public download servers (piston-data.mojang.com) to read block
  textures for color extraction. That file is cached locally and never redistributed.
- **Fabric Loader** — Apache License 2.0 — https://github.com/FabricMC/fabric-loader
- **Fabric API** — Apache License 2.0 — https://github.com/FabricMC/fabric
- **Yarn mappings** (build time only) — CC0 1.0 — https://github.com/FabricMC/yarn
- **Fabric Loom** (build time only) — MIT — https://github.com/FabricMC/fabric-loom
- **Mixin** (via Fabric Loader) — MIT — https://github.com/SpongePowered/Mixin
- **Gson** (provided by Minecraft) — Apache License 2.0 — https://github.com/google/gson
- **SLF4J** (provided by the platform) — MIT — https://www.slf4j.org/

## Test dependencies (not bundled)

- **JUnit 5** — EPL 2.0 — https://junit.org/

## Prior art

The concept of a browser-based live map is well established (Pl3xMap, Dynmap,
BlueMap, squaremap, …). The Explorer's Friend was designed after studying
[Pl3xMap](https://github.com/granny/Pl3xMap) (MIT License, © 2020-2023 William
Blake Galbreath) as a reference for the problem domain. No source code, assets,
palette data, or web frontend files from Pl3xMap or any of these projects were
copied into this project; all code and the web UI are original.
