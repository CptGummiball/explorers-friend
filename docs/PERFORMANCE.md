# Performance notes

## Design guarantees

- **Server thread**: the only work ever done there is (a) O(1) dirty-marking on block
  changes, (b) chunk snapshots at end-of-tick under a hard double budget
  (`render.tick-budget-micros`, default 1500 µs **and** `render.max-snapshots-per-tick`,
  default 4), (c) sampling the player list at the configured interval. Everything else
  — hashing, texture decoding, region parsing, PNG encoding, HTTP — runs on small,
  named, bounded worker pools (`EF-Scan`, `EF-Render`, `EF-Sched`, `EF-Web`).
- **No chunk loads**: full renders parse saved region files directly; live snapshots
  only touch chunks that are already loaded. Unloaded dirty chunks are rendered from
  disk instead.
- **Bounded memory**: no world copies, bounded queues (default 4096 tile jobs) with
  per-tile job merging, ~1.3 KiB per in-flight chunk snapshot, one reusable 1 MiB
  pixel buffer per render worker.
- **No global ForkJoinPool usage**; all pools are daemonized and shut down in order.

## Measured baseline (this repository's verification run)

Windows 11, Ryzen-class desktop, JDK 21.0.7, dedicated Fabric 1.21.1 server,
43 mods (Fabric API + test set), fresh world:

| Metric | Measured |
| --- | --- |
| First start: full mod inventory (43 JARs, SHA-256) | 156 ms |
| First start: complete color scan (1060 blocks, 1000 models) | 1.27 s |
| First start: total mod overhead until ready (incl. one-time 25 MB asset download) | 11.1 s |
| Second start (nothing changed): total mod overhead until ready | **1.7 s** |
| Live chunk snapshot (server thread) | ~1.4 ms per chunk, ≤ 4/tick |
| Base tile render+encode+write (512×512, worker thread) | ~21 ms average |
| Full render throughput (2-worker default) | ~2 regions/s on cold cache |

`/api/status` exposes these counters live (`render.avgTileMillis`,
`snapshots.snapshotNanos`, queue sizes) for your own measurements; JFR or
async-profiler can be attached normally since all mod threads are clearly named.

## Tuning

- Big servers with spare cores: raise `render.workers` (each worker holds ~2 MiB of
  buffers). Rendering is embarrassingly parallel per region.
- Struggling servers: lower `render.tick-budget-micros` / `max-snapshots-per-tick`
  and raise `update-debounce-seconds`. The map only gets *later*, never *heavier*.
- Huge modpacks: the first scan is dominated by texture decoding; subsequent starts
  hit the content-addressed texture cache. Adding one mod re-runs only the mapping,
  reusing ~all texture colors (typically > 90 % cache hits).
- `worlds.max-render-radius-blocks` caps full renders on giant worlds.
- Zoom levels cost ~33 % extra tile storage total (¼ + 1/16 + …).
