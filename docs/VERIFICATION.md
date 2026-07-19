# Verification protocol

Executed on 2026-07-18, Windows 11, JDK 21.0.7, Gradle 9.5.0 (wrapper), Loom 1.17.16.

## Build & tests

- `gradlew clean test check build` → **BUILD SUCCESSFUL** (all 60+ tests pass,
  access widener validated, remap OK).
- Release JAR: `build/libs/explorersfriend-0.1.0.jar` (~235 KiB). Contents verified:
  substituted `fabric.mod.json` (v0.1.0), mixin config, access widener, embedded web
  UI, icon, LICENSE + third-party notices, **no test classes**.
- Static environment check: no `net/minecraft/client` references anywhere in the
  compiled mod (`environment: "*"`, single `main` entrypoint) — safe in client
  installations and on dedicated servers.

## Dedicated-server runs (Fabric 0.16.14 launcher, Fabric API 0.116.14+1.21.1, headless)

| Run | Setup | Result |
| --- | --- | --- |
| 1 | Fresh server, fresh caches | Inventory 43 JARs in 156 ms (all "New"); one-time Mojang client-JAR download SHA-1-verified; **1060 blocks resolved in 1.27 s, 1000 models parsed, 0 errors, 4 fallbacks**; 64 biome tints; web on 127.0.0.1:8123; ready in 11.1 s |
| 1 (live) | Spawn chunks | 6 chunks snapshotted (~1.4 ms each on server thread), 26 tiles incl. zoom pyramid, avg 21.8 ms/tile |
| 1 (render) | `/efmap render minecraft:overworld` via RCON | 2 regions from disk, 55 chunks, zoom levels 0–4 written, finished in ~1 s; tile visually verified (snow biome, water depth gradient, village structures) |
| 2 | Restart, nothing changed | **Cache hits: 43 / New: 0**, block colors loaded from cache, ready in **1.7 s** |
| 3 | + `testbroken.jar` v1.0.0 (invalid JSON model override, corrupt PNG texture override, circular models) | "New: 1" detected; re-scan in **181 ms with 99.9 % texture-cache hits**; corrupt texture isolated as exactly one additional fallback (4→5); **0 errors, server fully operational** |
| 4 | Same filename, content/version changed to 1.0.1 | **"Changed: 1"** detected, fast re-scan again |
| 5/6 | Frontend fix verification | Browser loads UI, `/api/worlds`+`/api/players` polled, tiles fetched and painted onto the canvas (pixel-verified) |

## HTTP security checks (live server + unit tests)

- `/, /app.js, /style.css` served from classpath whitelist with ETag → 304.
- Tiles: strict regex, unknown dimension/zoom → 404, ETag → 304.
- `/tiles/../../eula.txt` → 404 (live), plus encoded-traversal cases in unit tests.
- POST → 405, oversized URI → 414, gzip on compressible content verified.

## Commands exercised live (RCON)

`status`, `render`, `update` (4 stale regions detected via mtime), `pause` (+ status
shows PAUSED), `resume`, `cancel`, `colors reload`, `cache stats`, `scan status`,
`scan mods`, `web status` — all with sensible feedback.

## Shutdown

Every run ended with the ordered shutdown
(`Shutting down... → Web server stopped → Shutdown complete`), exit code 0, state
persisted; the next start resumed from caches.

## Not verified live (by design instead)

- Client installation / integrated singleplayer server: cannot launch a graphical
  client in this environment. Covered by: zero client-class references (checked),
  `environment: "*"`, lifecycle events shared with the integrated server, no
  networking requirements. Should be smoke-tested once in a real client.

---

# Verification protocol (0.2.0 — overlay extension)

- `gradlew clean test check build` → **BUILD SUCCESSFUL** (90+ tests incl. overlay,
  claims, marker, banner-icon, skin-URL and 10k-load benchmarks; adapter code compiles
  against the real FTB Chunks 2101.1.20 / OPAC 0.27.8 API artifacts).
- Benchmarks (test output): 10k claims + 10k markers index build < 100 ms each,
  viewport bbox queries in the microsecond range, 10k-chunk merge < 1 s.

Dedicated-server runs 7–9 (with **Open Parties and Claims 0.27.8** + Forge Config API
Port installed, and a `claims-import.jsonc` zone):

| Check | Result |
| --- | --- |
| Provider detection log | `FTB Chunks: not installed` · `Open Parties and Claims: detected` · GriefPrevention documented as unavailable · `JSON import: active` · `Active providers: 2` |
| Absent claim mods | no class loading, no scans, no errors (run 1–6 had neither mod installed) |
| Missing OPAC dependency (run 7) | loader reported it cleanly; documented — not a mod defect |
| Claims API | imported zone served with semi-transparent fill `#4d…`/opaque border, ETag → **304** verified |
| Claim refresh | initial sync `1 area(s), revision 1`, no per-claim log spam |
| Marker command via RCON | quoted name `"Test Marker"` created, listed, served by `/api/v1/markers` |
| Marker persistence | survives restart (`Loaded 1 persistent marker(s)`), same id |
| Icons / heads / overlays / metrics | all HTTP 200; `/metrics` serves Prometheus text |
| Inventory on restart | `Cache hits: 48 | New: 0` (incl. the three new mods from run 8) |
| Shutdown | ordered, exit 0, markers flushed |

Not verifiable headless (no player can join): banner placement in-game and live player
head rendering — both covered by unit tests (banner id stability, design dedupe,
icon PNG validity, head composition, SSRF URL pinning) plus the code paths above;
recommend one in-game smoke test.

---

# Verification protocol (0.2.1 — chunk-load deadlock fix)

Bug: the banner heal path called `world.getBlockState`/`getBlockEntity` inside the
`CHUNK_LOAD` callback; the just-loading chunk is not registered yet, so the lookup
parked the server thread in `getChunkBlocking` → self-deadlock → watchdog kill.

Fix verification (dedicated server, run 11):

| Check | Result |
| --- | --- |
| Stale banner marker seeded at 5000/64/5000, then `forceload add 5000 5000` (reproduces the exact deadlock scenario post-startup) | Server stayed fully responsive, `/efmap status` answered immediately, **0 watchdog entries** |
| Heal path | Stale marker removed chunk-locally (API: 0 banner markers left), persisted on shutdown |
| Audit of all world accesses in event/server-thread paths | Only non-blocking lookups remain (`getChunk(..., create=false)`, biome lookups via `BIOMES`+`create=false`); `marker teleport` uses the vanilla teleport (documented, op-gated, not in a callback) |
| Bonus | Chunk loads now also register named banners from the chunk's own block entities (dispenser-/mod-placed or pre-existing banners) |
| Build | `gradlew clean test check build` → BUILD SUCCESSFUL (0.2.1) |


---

# 0.3.0 — Multi-version verification

Executed on 2026-07-19, Windows 11. Gradle JVM: JDK 25 (Temurin 25.0.1.8, required
by Loom for the 26.x targets); 1.21.x modules emit Java-21 bytecode via toolchains.

## Build & structural checks

- `gradlew buildAllVersions testAllVersions packageAllVersions verifyAllArtifacts`
  → **BUILD SUCCESSFUL**. All common-core unit tests pass; seven artifacts in
  `dist/` with `checksums-sha256.txt`, `release-manifest.json`, `compatibility.json`.
- `verifyAllArtifacts` per jar: fabric.mod.json version + non-empty Minecraft range,
  common core classes present, web UI present, zero test classes.
- Family boundaries were derived by compiling the identical adapter source against
  every stable release (probe module), then confirmed at runtime by smoke tests.
  The smoke tests caught two boundaries invisible to compilation: the
  `WorldChunk.setBlockState` third parameter changed `boolean`→`int` in 1.21.5
  (split of the former 1.21.2–1.21.8 family into two jars; the 1.21.9+/26.x mixins
  use `int`), and Fabric API's loader floor rose to 0.17.0 (1.21.9/1.21.10) and
  0.17.3 (1.21.11) — loader pins and `fabric.mod.json` ranges were raised to match.

## Dedicated-server smoke tests (scripts/smoke.py, headless, per artifact)

Each run: fresh Fabric server (launcher from meta.fabricmc.net, Fabric API from
Modrinth), our jar from `dist/`, then: server ready → jar-inventory scan → no client
classes → web UI + /api/status + /api/worlds reachable → markers loaded → player
layer served → claims detection logged → RCON stop with clean shutdown → second
start with cache hits.

| Artifact | Smoke-tested on | Java | Loader | Result |
| --- | --- | ---: | --- | --- |
| explorersfriend-fabric-1.21.1 | 1.21.1 | 21 | 0.16.14 | **passed** (all 9 checks) |
| explorersfriend-fabric-1.21.2-1.21.4 | 1.21.4 | 21 | 0.16.14 | **passed** |
| explorersfriend-fabric-1.21.5-1.21.8 | 1.21.5 | 21 | 0.16.14 | **passed** |
| explorersfriend-fabric-1.21.9-1.21.10 | 1.21.10 | 21 | 0.17.0 | **passed** |
| explorersfriend-fabric-1.21.11 | 1.21.11 | 21 | 0.17.3 | **passed** |
| explorersfriend-fabric-26.1 | 26.1.2 | 25 | 0.19.3 | **passed** |
| explorersfriend-fabric-26.2 | 26.2 | 25 | 0.19.3 | **passed** |

"Tested" flags in `release-manifest.json` are generated from these results
(`dist/test-results.json`); only smoke-passed artifacts carry `tested: true`.
Family members not smoke-tested directly (e.g. 1.21.3, 1.21.6, 26.1.1) are covered
by compile verification against their exact release + the family representative's
runtime run; this distinction is documented rather than glossed over.

## Negative tests (scripts/negative_smoke.py — 4/4 passed)

| Case | Expectation | Observed |
| --- | --- | --- |
| 26.2 jar on a 1.21.4 server | Loader refuses | "Incompatible mods" — refused before world load |
| Two variants at once (1.21.1 + 1.21.2-1.21.4 jars on 1.21.4) | Safe either way: refusal, or exactly one active instance (all ranges are disjoint) | Both behaviors observed across runs: loader refused the duplicate in the final suite; an earlier run selected the single compatible variant and started with exactly one instance. Both outcomes are safe. |
| Missing Fabric API | Loader refuses | "Incompatible mods" — dependency error |
| 26.2 artifact under Java 21 | Startup impossible | `UnsupportedClassVersionError` in the launcher's bundler phase; server never initializes |

## Notes

- The 1.21.1 artifact re-passed the full smoke suite after the multi-project
  restructure — no regression against the 0.2.1 behavior (same checks incl.
  second-start cache hits).
- OPAC/FTB integration runtime verification remains as in 0.2.x on 1.21.1 (live
  server run with OPAC + Forge Config API Port); other versions log honest
  detection/absence messages (see docs/MULTIVERSION.md availability matrix).
