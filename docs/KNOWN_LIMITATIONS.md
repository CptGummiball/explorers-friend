# Known limitations (0.1.0)

- **Minecraft 1.21.1 / Fabric only.** Other versions and loaders are out of scope.
- **One color per block.** Colors are resolved per block id (default/first blockstate
  variant), not per state — an oriented log renders with one tone. Manual overrides
  can compensate.
- **Server-side resource packs are not scanned**; only mod JARs and vanilla assets.
- **Blocks with dynamic/block-entity textures** (chests, signs, connected-texture
  mods) get the color of their static base texture or a fallback; use
  `block-colors.jsonc` or the API provider.
- **Relief shading at region borders** uses neutral light on the outermost
  west/north column (a subtle 1-px seam every 512 blocks on slopes).
- **Live updates need the chunk loaded or saved.** A chunk modified and unloaded is
  snapshotted at unload; the rare miss (crash between change and save) heals on the
  next load or `/efmap update`.
- **Full-render progress counting** includes concurrently finished live tiles, so the
  percentage can run slightly ahead; region counts are exact.
- **No sub-path reverse proxying** (`/map/` prefix) — use a subdomain.
- **`storage.max-tile-cache-mb` is accepted but not enforced yet** (reserved; tiles
  are only bounded by explored world size — leere Kacheln werden nie geschrieben).
- **No WebP tiles, no markers/layers API, no built-in auth** — candidates for later
  versions (see docs/ANALYSIS.md improvement table).
