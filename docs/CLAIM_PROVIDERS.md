# Claim provider integrations

> Provider availability differs per Minecraft version - see the matrix in
> [MULTIVERSION.md](MULTIVERSION.md). Notably: FTB Chunks has no Fabric builds for
> 1.21.2-1.21.10 and 26.x; the existing FTB build for 1.21.11 and OPAC's 26.x builds
> have no adapter yet (deferred, documented). The JSON import works everywhere.

Verified on 2026-07-18 for Minecraft 1.21.1 / Fabric.

| System | 1.21.1 Fabric | Official API | Change events | Colors/Names | Decision |
| --- | --- | --- | --- | --- | --- |
| **FTB Chunks** | ✓ `ftb-chunks-fabric 2101.1.20` (official FTB maven) | ✓ `dev.ftb.mods.ftbchunks.api` | ✓ `ClaimedChunkEvent.AFTER_*` (Architectury events) | Team `TeamProperties.COLOR` + `DISPLAY_NAME`; `shouldHideClaims()` respected | **Integrated** (compile-only adapter) |
| **Open Parties and Claims** | ✓ `0.27.8` (Modrinth) | ✓ `xaero.pac.*.api` | ✓ `IClaimsManagerTrackerAPI` listener | `getClaimsColor()` / `getClaimsName()` incl. sub-claims | **Integrated** (compile-only adapter) |
| **GriefPrevention** | ✗ | – | – | – | **Not integrable** (see below) |

## Why GriefPrevention is not integrated

GriefPrevention is a **Bukkit/Spigot/Paper plugin**; there is no Fabric port and its API
(`me.ryanhamshire.GriefPrevention.*`) builds on the Bukkit API, which does not exist in
a Fabric server. Implementing a fake integration would violate this project's rule
against invented APIs. Supported platforms of GriefPrevention: Paper-family servers.

**Bridge option:** the built-in **JSON import provider** reads
`config/explorersfriend/claims-import.jsonc` — any external system (including a
Paper server running GriefPrevention alongside, via an export script) can drop claim
data there; the file is polled cheaply by mtime and hot-reloaded. A dedicated
GriefPrevention export plugin would be a separate (Paper-side) project.

## Integration mechanics

- Adapters compile against the official API artifacts (`modCompileOnly`); **nothing is
  bundled**. Compile-time linking for interoperability does not redistribute any code
  (FTB Chunks: All Rights Reserved, API published for exactly this purpose on FTB's
  maven; OPAC: LGPL-3.0).
- At runtime, `FabricLoader.isModLoaded(...)` gates everything; adapter classes are
  only ever touched when their mod is present, so absent mods cause **no class
  loading, no scans, no `ClassNotFoundException`**.
- Provider APIs are treated as **server-thread-only**: refreshes copy a minimal raw
  snapshot on the server thread (bounded work), then rectangle merging, coloring and
  diffing happen on background workers.
- Refresh strategy per provider: change events (where offered) mark the provider
  dirty with debouncing; a configurable interval performs the authoritative sync;
  errors keep the last good data and back off exponentially.
- Further systems can be added by implementing one interface
  (`net.explorersfriend.claims.ClaimProvider`, in the version-independent `common`
  module) and registering it in `ClaimProviders.detect()` (each
  `platforms/fabric-*` module has its own copy).
