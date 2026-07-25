# Integration guide for claim/protection mod authors

This document tells you, as the author of a land-claim or protection mod/plugin,
exactly what The Explorer's Friend needs in order to display your claims on the
web map. There are three integration paths — pick the one that fits your project.
You never need a dependency on The Explorer's Friend for any of them.

| Path | Effort on your side | Platforms | What the map can show |
| --- | --- | --- | --- |
| A. JSON export file | write one file | all (Fabric, Quilt, NeoForge, Spigot, Paper) | everything: shapes, names, owners, teams, colors |
| B. Common Protection API | implement CPA | Fabric/Quilt | protected areas only (no names/owners) |
| C. First-class adapter | expose a public API, we write the adapter | per loader | everything, plus live change events |

---

## Path A — write the JSON import file (recommended baseline)

The Explorer's Friend watches a file named `claims-import.jsonc` and renders its
content as a claim layer. If your mod (or a companion script) keeps this file up
to date, you are fully integrated on **every** platform with zero API coupling.

**File location** (created by the server admin or by your mod):

- Fabric / Quilt / NeoForge: `config/explorersfriend/claims-import.jsonc`
- Spigot / Paper: `plugins/ExplorersFriend/claims-import.jsonc`

**Format** — a JSON array (comments and trailing commas are allowed; the file is
parsed as JSONC):

```jsonc
[
  {
    // Stable identifier. Keep it stable across writes so the map can diff
    // revisions; it is prefixed internally as "import:<id>".
    "id": "spawn-region",

    // Dimension key in namespaced form.
    "world": "minecraft:overworld",

    // Optional display fields. Omit any of them (or set null) and the map
    // falls back to its own deterministic color / hides the line in tooltips.
    "name": "Spawn",
    "owner": "Admins",
    "team": null,
    "color": "#ff8800",          // "#rrggbb"; fill opacity is a server setting

    // Geometry: block-coordinate rectangles [minX, minZ, maxX, maxZ]
    // (inclusive on both ends; corner order does not matter) ...
    "rects": [[-128, -128, 127, 127]],

    // ... and/or chunk coordinates [chunkX, chunkZ]. Chunks are merged into
    // rectangles automatically. You may use both fields in one entry.
    "chunks": [[12, -3], [12, -2]]
  }
]
```

**Behavioral contract:**

- The file is re-read when its modification time changes — write it atomically
  (write to a temp file, then rename) to avoid partial reads.
- A parse error never breaks the map: the previous data stays live and the
  broken entry index is logged. Entries with neither `rects` nor `chunks` are
  skipped silently.
- Update cadence is up to you. Writing on every claim change is fine; batching
  once every few seconds is friendlier for large claim counts.
- The provider id for the server's `claims.enabled-providers` config list is
  `jsonimport`.

---

## Path B — implement the Common Protection API (Fabric/Quilt)

If your Fabric mod implements Patbox's
[common-protection-api](https://github.com/Patbox/common-protection-api),
The Explorer's Friend picks it up automatically — no work needed beyond your CPA
implementation.

**Know the limits before choosing this path:** CPA is a point/area *query* API.
It cannot enumerate claims, owners or names, so the map samples chunks as the
server loads them (budgeted, 128 probes per tick) and persists the protected
set. Your claims therefore appear where players have actually been, labeled
"Protected area" with a deterministic color. If you want names, owners, exact
shapes or instant visibility, use Path A or C instead (or in addition — both can
be active at once; disable one via `claims.enabled-providers` if needed).

---

## Path C — let us build a first-class adapter against your API

This is what FTB Chunks, Open Parties and Claims and GriefPrevention have today:
a dedicated adapter with full fidelity and live updates. To make that possible
(and maintainable), your mod needs to provide:

1. **A published artifact we can compile against** — your API (or full mod) on a
   Maven repository (your own, Modrinth Maven, etc.), per supported loader and
   Minecraft version. We consume it compile-only and never bundle it; absent
   mods must cause zero class loading, so no transitive resolution surprises
   (mark heavy dependencies optional or keep the API artifact slim).
2. **A stable public entry point** to detect you and reach your data, e.g. a
   singleton or static accessor that is safe to call once the server started.
3. **Claim enumeration** — a way to list *all* current claims (a stream,
   collection or visitor), each exposing:
   - the dimension, as a registry key / namespaced id (`minecraft:overworld`),
   - the shape: either chunk positions or block-coordinate bounds — both work,
   - a stable claim identifier.
4. **Owner information** — at minimum an owner UUID; a ready-made display name
   is better (we resolve UUIDs through the server's profile cache otherwise).
   Tell us how admin/server claims are represented.
5. **Optional but valuable:** per-claim display name, per-claim or per-team
   color (any RGB form), sub-claim relationships, and a "hidden from maps" flag
   — if your mod has such a flag, we honor it and the claim never leaves the
   server.
6. **Change notifications** — events for claim create/delete/resize/transfer on
   your loader's event system, fired on the server thread. Without events we
   fall back to periodic refresh; with them the map updates within a debounce
   window.
7. **Threading statement** — one sentence in your API docs saying from which
   thread(s) enumeration is safe. We call it on the server thread by default
   and copy everything into immutable snapshots before rendering.
8. **Versioning** — tag or document which artifact versions belong to which
   Minecraft/loader versions, and avoid breaking the API within a Minecraft
   version line. Note your license; we link it, we never vendor your code.

**Process:** open an issue at
<https://github.com/CptGummiball/explorers-friend/issues> titled
`Claim adapter request: <your mod>` containing the Maven coordinates, a link to
the API classes/Javadoc, the supported loader+Minecraft versions, and a contact
for API questions. Adapters ship only after a real dedicated-server test with
your mod installed, and the support matrix in `MULTIPLATFORM.md` documents the
result.

---

## What every integration gets from the map (and must tolerate)

- Claims render as merged rectangles: semi-transparent fill, opaque border.
  Color priority: explicit claim color → team color → owner-derived
  deterministic color → server default.
- Tooltips show name/owner/team **subject to the server's privacy settings**
  (`claims.show-owner`, `claims.show-name`, `claims.show-team`, per-world
  exclusions) — do not assume your metadata is always displayed.
- Your data is copied into immutable snapshots and served with revision/ETag
  semantics; refreshes are debounced and capped, so high-frequency events are
  safe on your side.
- A missing, disabled or erroring integration must never prevent the map (or
  your mod) from starting — our side guarantees the same: adapters are detected
  at startup, isolated behind availability checks, and log honestly whether
  they are active, disabled by config, or not installed.
