# Configuration reference

File: `config/explorersfriend/config.jsonc` (JSON with `//` comments). Every value is
validated on load; out-of-range or mistyped values fall back to the default with a
console warning — a broken config never crashes the server. Reload at runtime with
`/efmap reload` (thread/storage changes need a restart; a changed `web.bind`/`web.port` is applied live with `/efmap web restart`).

## `web`

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Embedded map web server on/off |
| `bind` | `"127.0.0.1"` | Bind address. Loopback = only local/reverse-proxy access. `"0.0.0.0"` exposes the map directly |
| `port` | `8080` | HTTP port |
| `public-base-url` | `""` | Absolute URL shown by `/efmap web status` when behind a reverse proxy |
| `title` | `"The Explorer's Friend"` | Browser page title |
| `threads` | `2` | HTTP worker threads (1–16) |
| `gzip` | `true` | Compress JSON/HTML/CSS/JS responses |
| `connection-limit` | `64` | Max simultaneous connections (503 beyond) |
| `idle-timeout-seconds` | `30` | Request/connection time bound |

## `render`

| Key | Default | Description |
| --- | --- | --- |
| `workers` | `2` | Background render threads (1–32) |
| `tick-budget-micros` | `1500` | Hard per-tick time budget for live chunk snapshots |
| `max-snapshots-per-tick` | `4` | Hard per-tick snapshot count cap |
| `max-queued-tiles` | `4096` | Bounded render queue (backpressure limit) |
| `zoom-levels` | `4` | Zoom-out levels above the 1 px/block base zoom |
| `height-shading` | `true` | Relief shading from neighbour heights |
| `water-depth-shading` | `true` | Darker water with depth |
| `update-debounce-seconds` | `5` | Quiet period before a changed chunk re-renders |
| `update-max-delay-seconds` | `60` | Continuously changing chunks still render at least this often |
| `full-render-on-first-start` | `false` | Render all existing regions once after the very first start |
| `render-new-chunks` | `true` | Render chunks when first explored/generated |

## `scan`

| Key | Default | Description |
| --- | --- | --- |
| `threads` | `2` | Startup scan worker threads |
| `download-vanilla-assets` | `true` | Dedicated servers: one-time, SHA-1-verified download of the vanilla client JAR from Mojang for block textures. Disabled → built-in fallback palette |
| `animated-textures` | `"first_frame"` | `first_frame` or `average` (part of the color cache key) |
| `exclude-mods` | `[]` | Mod IDs skipped during resource scanning |
| `exclude-namespaces` | `[]` | Resource namespaces skipped (their blocks get the unknown color) |
| `max-texture-edge` | `4096` | Safety: larger textures are skipped |
| `max-zip-entries` | `100000` | Safety: archives with more entries are skipped |
| `max-entry-bytes` | `33554432` | Safety: max decompressed size per resource |

## `storage`

| Key | Default | Description |
| --- | --- | --- |
| `data-dir` | `"explorersfriend"` | Data directory (tiles + caches), relative to the server directory |
| `max-tile-cache-mb` | `0` | Reserved for tile pruning; `0` = unlimited |
| `prune-caches-on-start` | `false` | Delete and rebuild all caches on the next start |

## `worlds`

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `["*"]` | Dimension IDs to map (`"*"` = all), e.g. `["minecraft:overworld"]` |
| `disabled` | `[]` | Dimensions to exclude (wins over `enabled`) |
| `max-render-radius-blocks` | `0` | Limit full renders to a radius around spawn; `0` = unlimited |

## `players`

| Key | Default | Description |
| --- | --- | --- |
| `show` | `true` | Player markers on the map |
| `update-interval-seconds` | `2` | Browser position refresh interval |
| `hide-invisible` | `true` | Hide players with invisibility |
| `hide-spectators` | `true` | Hide spectator-mode players |
| `position-rounding` | `1` | Round positions to N blocks (privacy blur), e.g. `16` |

## `logging`

| Key | Default | Description |
| --- | --- | --- |
| `progress-interval-seconds` | `5` | Interval of aggregated progress lines |
| `debug` | `false` | Per-file/per-resource DEBUG logging |

## `blocks`

| Key | Default | Description |
| --- | --- | --- |
| `unknown-block-color` | `"#7f7f7f"` | Color for unresolvable blocks |
| `exclude-blocks` | `[]` | Block IDs never shown (the block below is rendered) |

## `claims`

`enabled`, `default-visible-in-ui`, `refresh-interval-seconds` (10–3600, default 60),
`fill-opacity` (0.05–0.9, default 0.30 — fills are always semi-transparent),
`border-opacity` (default 1.0), `border-width` (px at base zoom),
`show-owner` / `show-name` / `show-team` (privacy), `enabled-providers`
(`"*"` or ids `ftbchunks`, `openpartiesandclaims`, `jsonimport`), `disabled-worlds`,
`max-claims-per-response`, `default-color`. Import file:
`config/explorersfriend/claims-import.jsonc` (see docs/CLAIM_PROVIDERS.md).

## `players` (extended in 0.2.0)

New keys: `default-visible-in-ui`, `position-delay-seconds` (anti-stalking),
`show-names`, `show-coordinates`, `anonymize-names`, `allow-external-skin-lookup`,
`skin-cache-hours`, `hidden-players` (names/UUIDs opt-out), `disabled-worlds`.

## `markers`

`enabled`, `default-visible-in-ui`, `banners-default-visible-in-ui`, `max-per-player`,
`max-total`, `allow-player-creation`, `allow-banner-markers`,
`remove-marker-with-banner`, `show-creator`, `show-coordinates`, `disabled-worlds`,
`save-interval-seconds`. External icon directories are deliberately unsupported
(SVG = XSS vector); the built-in library covers 23 icons.

## `markers.custom-icons` (since 0.4.0)

User-supplied marker icons from `config/explorersfriend/icons/`. PNG/JPEG only —
every file is decoded and re-encoded server-side before serving (uploaded bytes
never reach a browser); SVG is deliberately not supported for user content. The
file name (without extension) becomes the icon id, used as `custom:<name>` in
marker commands; names must match `[a-z0-9_-]{1,32}`.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. |
| `max-count` | `64` | Maximum icons loaded (alphabetical; the rest is logged and skipped). |
| `max-edge-px` | `128` | Maximum image width/height. |
| `max-file-kib` | `256` | Maximum file size per icon. |

Reload together with the config: `/efmap reload`.

## `waystones` (since 0.4.0)

Only active when the [Waystones](https://modrinth.com/mod/waystones) mod is
installed; named waystones then appear as their own toggleable layer. Sharestones
and shard-bound waystones are never shown.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch for the layer. |
| `only-global` | `false` | `true` = only waystones with GLOBAL visibility. |
| `show-owner` | `false` | Show the owner name in tooltips. |
| `refresh-seconds` | `60` | Refresh interval for the waystone list. |
| `disabled-worlds` | `[]` | Dimensions excluded from the layer. |
| `default-visible-in-ui` | `true` | Initial state of the UI toggle. |

## `markers.custom-icons` (since 0.4.0)

User-supplied marker icons from `config/explorersfriend/icons/`. PNG/JPEG only -
every file is decoded and re-encoded server-side before serving; SVG is
deliberately not supported for user content. The file name (without extension)
becomes the icon id, used as `custom:<name>` in marker commands; names must match
`[a-z0-9_-]{1,32}`.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. |
| `max-count` | `64` | Maximum icons loaded (alphabetical; the rest is logged and skipped). |
| `max-edge-px` | `128` | Maximum image width/height. |
| `max-file-kib` | `256` | Maximum file size per icon. |

Reload together with the config: `/efmap reload`.

## `waystones` (since 0.4.0)

Only active when the [Waystones](https://modrinth.com/mod/waystones) mod is
installed; named waystones then appear as their own toggleable layer. Sharestones
and shard-bound waystones are never shown.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch for the layer. |
| `only-global` | `false` | `true` = only waystones with GLOBAL visibility. |
| `show-owner` | `false` | Show the owner name in tooltips. |
| `refresh-seconds` | `60` | Refresh interval for the waystone list. |
| `disabled-worlds` | `[]` | Dimensions excluded from the layer. |
| `default-visible-in-ui` | `true` | Initial state of the UI toggle. |

## `performance`

`auto-throttle` (default true), `mspt-pause-threshold` (45), `mspt-resume-threshold`
(35): background rendering pauses automatically while the server is overloaded.
`web.metrics-enabled` controls the Prometheus `/metrics` endpoint.

## Manual block colors

`config/explorersfriend/block-colors.jsonc` — highest priority, reloadable via
`/efmap colors reload`:

```jsonc
{
  // simple: #rrggbb or #aarrggbb
  "minecraft:stone": "#7a7a7a",
  // with biome tint category: none | grass | foliage | water
  "somemod:magic_grass": { "color": "#8ab84f", "tint": "grass" }
}
```

## Cache maintenance

All caches live in `<data-dir>/cache/` and are safe to delete at any time (they
rebuild on the next start). Options, from gentle to thorough:

1. `/efmap cache stats` — see what is used.
2. `/efmap cache prune confirm` — delete scan/color caches, keep tiles.
3. `/efmap cache prune confirm tiles` — also delete every rendered tile.
4. Config `storage.prune-caches-on-start: true` — one-shot rebuild on next boot.

Corrupt cache files are never fatal: they are moved aside as `*.corrupt-N` and rebuilt.
