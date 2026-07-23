[SIZE=6][B]The Explorer's Friend — live browser world map for Spigot & Paper[/B][/SIZE]

[B]A lightweight, fully server-side web map plugin. One jar, every Minecraft version from 1.21.1 to 26.x. No client mod, no NMS, no database.[/B]

Your players open the map in their browser — pan, zoom, switch dimensions, watch each other move in real time. You install a single plugin and get a rendered topographic map of everything that has ever been explored.

[SIZE=5][B]Why this plugin[/B][/SIZE]
[LIST]
[*]🗺️ [B]Real topographic tiles[/B] — relief shading, water depth, biome-tinted grass and foliage. Full renders read the region files directly on worker threads: [B]not a single chunk is loaded[/B] and the main thread stays untouched.
[*]⚡ [B]Built for busy servers[/B] — live updates come from thread-safe chunk snapshots with hard per-tick budgets, all queues are bounded, and rendering pauses automatically while your TPS is under pressure.
[*]🎨 [B]Custom content just works[/B] — block colors are computed from the actual textures (vanilla assets are fetched once from Mojang, SHA-verified; plugin jars are scanned for resources too). Manual color overrides included.
[*]🧭 [B]One jar for every version[/B] — pure Bukkit API (api-version 1.21), no version-specific NMS hacks. The same file runs on 1.21.1 through 26.x, on Spigot, Paper and Paper forks (Purpur & friends). Paper is detected automatically.
[/LIST]

[SIZE=5][B]Features[/B][/SIZE]
[LIST]
[*][B]Live player layer[/B] with real skin heads (privacy options: hide invisible/spectators, position rounding & delay, name anonymization, per-player opt-out)
[*][B]GriefPrevention claims[/B] on the map — detected automatically (softdepend), shown as exact block rectangles including subclaims, with live updates on create/resize/delete. No GriefPrevention? The layer simply stays off.
[*][B]Markers[/B] via [ICODE]/efmap marker add|list|remove[/ICODE] with a built-in icon library and your own custom icons (drop PNGs into [ICODE]plugins/ExplorersFriend/icons/[/ICODE])
[*][B]JSON claim import[/B] as a bridge for any other protection plugin
[*][B]Web UI layer panel[/B] — every layer individually toggleable, hover tooltips, clustering, share links; settings persist in the browser
[*][B]Ops tooling[/B] — [ICODE]/efmap status|render|pause|resume|cancel[/ICODE], HTTP ETag caching, JSON status API, optional Prometheus [ICODE]/metrics[/ICODE]
[*][B]Privacy-hardened & secure by default[/B] — web server binds to 127.0.0.1 until you expose it (reverse-proxy guide included), strict path validation, bounded connections
[/LIST]

[SIZE=5][B]Quick start[/B][/SIZE]
[LIST=1]
[*]Drop [ICODE]explorersfriend-spigot-paper-<version>.jar[/ICODE] into [ICODE]plugins/[/ICODE].
[*]Start the server. First start downloads the vanilla client jar once from Mojang (block textures are not in the server jar; SHA-1-verified, cached).
[*]Open [B]http://localhost:8080/[/B] — then render what's already explored: [ICODE]/efmap render minecraft_overworld[/ICODE]
[/LIST]

Configuration lives in [ICODE]plugins/ExplorersFriend/config.jsonc[/ICODE] (fully commented). Permissions use the standard Bukkit system ([ICODE]explorersfriend.command.*[/ICODE], defaults: admin commands = op, marker creation = everyone, configurable).

[SIZE=5][B]Good to know[/B][/SIZE]
[LIST]
[*]Java 21 for Minecraft 1.21.x servers, Java 25 for 26.x — same as the server itself.
[*]On Minecraft 26.x, Mojang removed the built-in worldgen data files, so biome tinting uses neutral defaults there (map stays fully functional; 1.21.x is unaffected).
[*]Markers, config, manual colors and tiles are portable: you can move a world from a Fabric/NeoForge server to Paper (or back) and keep your map data — the same map core runs on all of them.
[/LIST]

[SIZE=5][B]Links[/B][/SIZE]
[LIST]
[*]Source, documentation and issue tracker: [URL='https://github.com/CptGummiball/explorers-friend']github.com/CptGummiball/explorers-friend[/URL]
[*]All-platform downloads (Fabric, Quilt, NeoForge): see the Modrinth page or GitHub releases. This page ships the Spigot/Paper plugin build.
[/LIST]

[I]License: MIT. Fully original code — no Pl3xMap/Dynmap/BlueMap code or assets.[/I]
