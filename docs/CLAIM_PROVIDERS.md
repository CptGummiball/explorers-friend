# Claim provider integrations

> Provider availability differs per Minecraft version - see the matrix in
> [MULTIVERSION.md](MULTIVERSION.md). Since 0.4.0: FTB Chunks adapters exist for
> 1.21.1 **and 1.21.11** (no Fabric builds exist for the versions in between or
> 26.x), the OPAC adapter covers **all** supported versions including 26.x, and the
> **Common Protection API** provider (GOML ReServed etc.) works everywhere. The
> JSON import works everywhere.

## Common Protection API (GOML ReServed and others) — since 0.4.0

The [Common Protection API](https://github.com/Patbox/common-protection-api) is a
query-only interface: it answers "is this position/area protected?" but cannot
enumerate claims, owners or names. The provider therefore probes chunks as the
server loads them (queued from the chunk-load event, executed on the server tick
with a fixed budget of 128 probes/tick — never inside the chunk callback) and
persists the protected set in `explorersfriend/protected-chunks.json`. Consequences,
honestly stated:

- The overlay shows protected areas **where players have actually been** since the
  provider was enabled; unexplored claims appear once their chunks load.
- Chunks whose protection was removed heal out the next time they load.
- No owner or claim names — areas are labeled "Protected area" and use the default
  claim color. Provider id for `claims.enabled-providers`: `commonprotection`.

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
