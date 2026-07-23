# Installation per platform

One rule everywhere: install exactly **one** Explorer's Friend artifact that
matches your platform and Minecraft version, plus the listed dependencies.
The web map then runs on `http://<server>:8080` (configurable). The full
artifact/version matrix lives in [MULTIPLATFORM.md](MULTIPLATFORM.md) and
[MULTIVERSION.md](MULTIVERSION.md).

## Fabric

1. Install the [Fabric server launcher](https://fabricmc.net/use/server/) for
   your Minecraft version (loader ≥0.16; 1.21.9+ needs ≥0.17.0, 1.21.11 ≥0.17.3,
   26.x ≥0.19).
2. Drop **Fabric API** and `explorersfriend-fabric-<mc-range>-<ver>.jar` into `mods/`.
3. Start the server. Java 21 for 1.21.x, Java 25 for 26.x.

## Quilt

The **Fabric artifact is the Quilt artifact** — Quilt loads it natively
(verified on Quilt Loader 0.30 for 1.21.1 and 26.2; the status endpoint then
reports `"platform": "quilt"`).

1. Install the Quilt server for your Minecraft version.
2. Drop Fabric API (or QFAPI/QSL where available) and the matching
   `explorersfriend-fabric-<mc-range>-<ver>.jar` into `mods/`.

## NeoForge

1. Install the NeoForge server for your Minecraft version (21.1.x for MC 1.21.1,
   26.2.x for MC 26.2).
2. Drop `explorersfriend-neoforge-<mc>-<ver>.jar` into `mods/`. No further
   dependencies.
3. Java 21 for 1.21.x, Java 25 for 26.x.

## Forge

Not shipped in this release — see the decision in
[MULTIPLATFORM.md](MULTIPLATFORM.md) §3. Use NeoForge on 1.21+ servers.

## Spigot

1. Any Spigot build for MC 1.21.1–26.x (BuildTools).
2. Drop `explorersfriend-spigot-paper-<ver>.jar` into `plugins/`. One jar covers
   every supported Minecraft version (pure Bukkit API, no NMS).
3. Optional: GriefPrevention is detected automatically (softdepend).

## Paper (and Purpur & friends)

Same plugin jar and steps as Spigot — Paper is detected at runtime
(`"platform": "paper"`), no separate artifact. Verified on Paper 1.21.1 and
26.1.2.

## Dependencies at a glance

| Platform | Required | Optional integrations |
| --- | --- | --- |
| Fabric/Quilt | Fabric API | LuckPerms (permission nodes), FTB Chunks (1.21.1/1.21.11), OPAC, GOML/CPA, Waystones |
| NeoForge | — | Waystones; OPAC adapter planned |
| Spigot/Paper | — | GriefPrevention |
