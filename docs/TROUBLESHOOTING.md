# Troubleshooting

**The map page is empty / "Map data unavailable".**
The scan may still be running — watch the console for
`[ExplorersFriend/Init] Ready in ...`. If the web server failed to bind, you'll see
`[ExplorersFriend/Web] Could not start the web server` (usually the port is taken →
change `web.port`).

**I can't reach the map from another machine.**
Default bind is `127.0.0.1` on purpose. Use a reverse proxy
([REVERSE_PROXY.md](REVERSE_PROXY.md)) or set `web.bind: "0.0.0.0"` and open the port
in your firewall.

**Mod blocks are gray.**
Gray = unresolvable (`blocks.unknown-block-color`). Check `/efmap scan status` for
fallback counts and enable `logging.debug` to see per-block reasons in
`<data-dir>/cache/block-colors.json` (`reason` field). Fix with an entry in
`block-colors.jsonc`, then `/efmap colors reload`.

**Vanilla blocks are gray on a dedicated server.**
Block textures aren't in the server JAR. Either allow the one-time Mojang asset
download (`scan.download-vanilla-assets: true`, default) or place a client JAR at
`<data-dir>/cache/vanilla-client-<version>.jar` manually.

**Already rendered areas keep old colors after changing colors.**
Tiles are only re-rendered when their chunks change. Force it:
`/efmap update <dimension>` (only changed regions) or `/efmap render <dimension>`.

**"Another process holds the cache lock" / READ-ONLY.**
Two server instances share one data dir. The second instance serves the map but
won't write. Give each server its own `storage.data-dir`. A stale lock from a crash
clears on the next start (locks are OS-level and die with the process).

**A cache file is reported corrupt.**
It was moved aside as `*.corrupt-N` and rebuilt automatically; delete the backup when
you don't need it for a bug report. Full reset: `/efmap cache prune confirm tiles`.

**Nether looks odd / shows the roof.**
Dimensions with a ceiling are scanned below the roof automatically. If a modded
ceiling dimension misbehaves, exclude it (`worlds.disabled`) and open an issue.

**Console shows `Tile ... failed permanently`.**
Three retries failed — usually a truly corrupt region file. The queue keeps running;
the region renders again once the file changes. Check disk space and the stack trace.

**The map lags behind the world.**
Increase `render.workers`, or lower `update-debounce-seconds`. Watch
`/efmap status` — a constantly full queue means the workers can't keep up.
