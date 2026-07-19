# API for mod developers

Stability: **experimental** in 0.1.x — the concepts stay, signatures may still move.
Everything lives in one class: `net.explorersfriend.api.ExplorersFriendApi`.
Register during your own `onInitialize`; callbacks run on background workers — never
block them or touch live world state from them.

## Provide colors for special blocks

For blocks whose textures the scanner cannot represent (block-entity renderers,
connected textures, shader-driven blocks):

```java
ExplorersFriendApi.registerBlockColorProvider(blockId -> {
    if (blockId.equals("mymod:chromatic_altar")) {
        return 0xFF8040C0; // ARGB
    }
    return null; // leave everything else alone
});
```

Priority order: **manual user overrides** (`block-colors.jsonc`) → **API providers**
→ automatic texture resolution → built-in fallback palette → unknown color.

## Hide players (vanish integrations)

```java
ExplorersFriendApi.registerPlayerVisibilityProvider(player ->
        !MyVanishMod.isVanished(player)); // false = never shown on the map
```

Called on the server thread during the periodic sample — keep it cheap. Any veto
hides the player; built-in invisibility/spectator/config rules always apply on top.
A throwing provider hides the player defensively.

## Events

```java
ExplorersFriendApi.registerScanCompleteListener((totalBlocks, fallbackBlocks) ->
        LOGGER.info("map colors ready: {} blocks", totalBlocks));

ExplorersFriendApi.registerTileRenderedListener((dimensionSlug, zoom, x, z) -> {
    // e.g. invalidate your own overlay cache for that tile
});
```

`TileRenderedListener` fires for every written (or emptied) tile including zoom
levels; filter on `zoom == 0` for base tiles. Tile → world math: at zoom 0 one tile
is one region, i.e. blocks `[x*512, x*512+511]` × `[z*512, z*512+511]`.

## HTTP endpoints (read-only, stable-ish)

| Endpoint | Content |
| --- | --- |
| `/api/worlds` | Title, player poll interval, per-dimension: id, slug, zoom levels, spawn |
| `/api/status` | Live diagnostics: queues, render/snapshot counters, scan summary |
| `/api/players` | Visible players: name, uuid, world slug, rounded x/z, yaw |
| `/tiles/{slug}/{zoom}/{x}_{z}.png` | Map tiles (ETag revalidation) |

## Out of scope (planned, not in 0.1.0)

Marker/layer registration, custom renderers, and web-UI extension points. Open an
issue with your use case before building on internals — internal packages
(`net.explorersfriend.*` except `.api`) carry no compatibility promise.
