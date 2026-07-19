# Known limitations (0.3.0)

- **Fabric only.** Seven artifacts cover Minecraft 1.21.1 – 26.2 (see
  [MULTIVERSION.md](MULTIVERSION.md)); other loaders (Forge/NeoForge, Paper
  plugins) are out of scope.
- **Claim-provider integrations differ per Minecraft version.** The FTB Chunks
  adapter exists only for 1.21.1 (FTB's 1.21.11 build has no adapter yet), the
  OPAC adapters cover the 1.21.x targets (no 26.x adapter yet); the JSON import
  works everywhere. Details in [MULTIVERSION.md](MULTIVERSION.md).
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
  are only bounded by explored world size — empty tiles are never written).
- **No WebP tiles** (re-evaluated and rejected in 0.2.0: the JDK has no WebP encoder
  and bundling native codecs contradicts the zero-dependency design), **no
  marker/layer API for other mods** (in-game `/efmap marker` commands and banner
  markers exist since 0.2.0), **no built-in auth** (use a reverse proxy, see
  [REVERSE_PROXY.md](REVERSE_PROXY.md)).
