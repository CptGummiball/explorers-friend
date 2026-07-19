# The Explorer's Friend

**A lightweight, fully server-side browser world map for Fabric servers.**
Minecraft 1.21.1 · Fabric · Java 21

The Explorer's Friend renders a live topographic map of your world and serves it as a
web page from an embedded HTTP server. It is built around three ideas:

1. **Server-side only.** Install one JAR on the server — players join with a completely
   vanilla client and open the map in their browser. No custom network protocol, no
   client mod, nothing to install for players. (Installing the JAR in a client
   instance is harmless: singleplayer worlds get a map through the integrated server.)
2. **Mod blocks just work.** On startup the mod inventories every installed mod JAR
   (SHA-256 content identity, nested jar-in-jar included), resolves each registered
   block through its *blockstate → model → texture* chain and computes a
   representative map color from the actual texture — in linear color space, alpha-
   aware, with outlier trimming. Results are cached content-addressed, so unchanged
   mods are never scanned twice, and identical textures across mods are analyzed once.
3. **Minimal server impact.** Nothing heavy ever runs on the server thread. Live world
   access is limited to budgeted chunk snapshots (default ≤ 1.5 ms and ≤ 4 chunks per
   tick); full renders read region files from disk on worker threads without loading
   a single chunk. All queues are bounded with backpressure and job merging.

## Installation (server)

1. Install a [Fabric server](https://fabricmc.net/use/server/) for Minecraft 1.21.1
   (Java 21 or newer).
2. Drop **explorersfriend-x.y.z.jar** and **[Fabric API](https://modrinth.com/mod/fabric-api)**
   into the `mods/` folder.
3. Start the server. The map is available at **http://localhost:8080/** by default.

On a dedicated server the first start downloads the official vanilla client JAR once
from Mojang's servers (block textures are not part of the server JAR). This is
verified against Mojang's SHA-1 checksums, cached, and can be disabled with
`scan.download-vanilla-assets: false` (a built-in fallback palette is used then).

The web server binds to `127.0.0.1` by default — safe out of the box. To make the map
public, either put it behind a reverse proxy (recommended, see
[docs/REVERSE_PROXY.md](docs/REVERSE_PROXY.md)) or set `web.bind` to `0.0.0.0`.

## First map

Explored chunks appear automatically as players move around. To render everything
that already exists:

```
/efmap render minecraft:overworld
```

Progress is shown in the console and via `/efmap status`. Full renders are resumable:
an interrupted render continues automatically on the next start.

## Overlays: claims, players, markers

Three independent overlay layers ship with the map — all separate from the rendered
tiles, individually toggleable in the web UI (state persists in the browser):

- **Claims** — auto-detected integrations for FTB Chunks and Open Parties and Claims
  (official APIs; nothing bundled), plus a JSON import file for everything else.
  Semi-transparent fills, opaque borders, provider/team colors, hover details.
  See [docs/CLAIM_PROVIDERS.md](docs/CLAIM_PROVIDERS.md).
- **Players** — live positions with real skin heads (cached, privacy-hardened),
  vanish-aware via the `PlayerVisibilityProvider` API, optional position delay,
  rounding and name anonymization.
- **Markers** — persistent markers via `/efmap marker ...` with a built-in icon
  library, plus **banner markers**: rename a banner in an anvil and place it — it
  appears on the map with its actual pattern; breaking it removes it.

## Commands (`/efmap`, permission level 2)

| Command | Effect |
| --- | --- |
| `/efmap status` | Queue, render statistics, web address, active full renders |
| `/efmap render <dimension> [radius]` | Full render (optionally limited around spawn) |
| `/efmap update <dimension>` | Re-render only regions whose files changed |
| `/efmap pause` / `resume` | Pause/resume all rendering |
| `/efmap cancel [dimension]` | Cancel queued full-render work |
| `/efmap reload` | Reload configuration + manual colors |
| `/efmap scan status` / `scan mods` | Inventory and color-scan summary |
| `/efmap colors reload` | Re-apply `block-colors.jsonc` without restart |
| `/efmap cache stats` | Tile/cache disk usage |
| `/efmap cache prune confirm [tiles]` | Delete caches (permission level 3) |
| `/efmap web status` | Bind address and per-dimension map links |
| `/efmap web restart` | Apply a changed `web.bind`/`web.port` without restarting the server |

Marker commands (`/efmap marker ...`, players may manage their own markers by default):
`add <name> [icon]`, `add-at <dim> <x> <y> <z> <name> [icon]`, `list`, `info`,
`remove`, `rename`, `icon`, `move`, `hide`/`show`, `description`, `category`,
`categories`, `icons`, `teleport` (op).

## Configuration

`config/explorersfriend/config.jsonc` — fully commented, validated, safe defaults;
invalid values fall back with a warning instead of crashing. See
[docs/CONFIGURATION.md](docs/CONFIGURATION.md) for every option.

Manual block colors (always win over automatic ones):
`config/explorersfriend/block-colors.jsonc`

```jsonc
{
  "minecraft:stone": "#7a7a7a",
  "somemod:crystal": { "color": "#80c0ffee", "tint": "none" }
}
```

## Privacy

Player markers are optional (`players.show`), invisible players and spectators are
hidden by default, positions can be rounded to a coarse grid
(`players.position-rounding`), and the position update interval is configurable.
No other personal data is exposed. See
[docs/SECURITY_PRIVACY.md](docs/SECURITY_PRIVACY.md).

## Documentation

- [Configuration reference](docs/CONFIGURATION.md)
- [Architecture](docs/ARCHITECTURE.md) · [Specification](docs/SPECIFICATION.md)
- [Performance notes](docs/PERFORMANCE.md)
- [Security & privacy](docs/SECURITY_PRIVACY.md) · [Reverse proxy guide](docs/REVERSE_PROXY.md)
- [API for mod developers](docs/API.md)
- [Building & development](docs/DEVELOPMENT.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md) · [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Changelog](CHANGELOG.md)

## Issues
Issues are processed every Wednesday.

[CREATE ISSUE](https://github.com/CptGummiball/explorers-friend/issues)


## License

MIT — see [LICENSE](LICENSE). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
The Explorer's Friend is an original implementation; it studies the problem space of
existing mappers (Pl3xMap, Dynmap, BlueMap, squaremap) but contains no code or assets
from them.
