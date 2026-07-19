# Security & privacy

## Network exposure

- Default bind is **127.0.0.1** — the map is not reachable from outside until you
  deliberately change it. For public maps, prefer a reverse proxy with TLS (and, if
  wanted, HTTP auth) over binding `0.0.0.0`; see [REVERSE_PROXY.md](REVERSE_PROXY.md).
- The embedded server only answers `GET`/`HEAD`, enforces a URI length limit, a
  connection cap (503 beyond `web.connection-limit`), bounded request/response times,
  and sends `X-Content-Type-Options: nosniff` everywhere.
- There is deliberately **no** built-in account system; authentication belongs into
  the reverse proxy layer.

## Path traversal / file disclosure

- Static UI files are served from an explicit classpath whitelist; no filesystem path
  is ever derived from a URL.
- Tile URLs are matched by a strict regex (`/tiles/<slug>/<zoom>/<x>_<z>.png`); the
  slug must be a known, enabled dimension, zoom and coordinates are bounded integers.
  Nothing else on disk is reachable. (Covered by automated tests.)

## Hostile resource files

Mod JARs are third-party input. The scanner enforces: ZIP entry-count limit,
per-entry decompressed-size limit, texture edge-length limit checked from the image
header *before* pixel decoding (PNG-bomb protection), path normalization (no `..`,
no absolute paths), no symlink following out of mod roots, model recursion depth and
texture-variable hop caps (cycle-proof), and NBT depth/size caps in the region
parser. One broken file degrades exactly one block/tile, never the scan or the queue.

## Player data

Served under `/api/players` (browser polling):

- fully disableable (`players.show: false` → empty list, nothing sampled),
- spectators and invisible players are hidden by default,
- positions can be rounded to an N-block grid (`players.position-rounding`),
- payload contains only: name, UUID, dimension, rounded x/z, yaw — no health,
  inventory, chat, or IP data,
- the endpoint is `Cache-Control: no-store`.

The tiles themselves reveal explored terrain including player builds — that is the
purpose of a map. Use `worlds.disabled` or `blocks.exclude-blocks` to hide whole
dimensions or specific blocks.

## Outbound connections

Exactly one, optional: the one-time download of the vanilla client JAR from
`piston-meta.mojang.com` / `piston-data.mojang.com` (Mojang's official distribution,
SHA-1 verified, host pinned). Disable with `scan.download-vanilla-assets: false`.
Nothing is ever uploaded anywhere.
