# Phase A – Analyse des Referenzprojekts Pl3xMap

Vollständige Detailanalyse: [REFERENCE_ANALYSIS_PL3XMAP.md](REFERENCE_ANALYSIS_PL3XMAP.md)
(analysierter Stand: Commit `0ae3449c`, 2026-07-11).

## Versionsmatrix

| Komponente | Pl3xMap (aktuell) | The Explorer's Friend (dieses Projekt) |
| --- | --- | --- |
| Minecraft | 26.2 (Multiversion-Historie 1.13+) | **1.21.1** (Zielvorgabe) |
| Java | 21+ | **21** |
| Loader | Fabric + Bukkit/Paper (Uber-JAR) | **Fabric** (eigenständig) |
| Fabric Loader | 0.19.3 | 0.16.14 (Mindestanforderung ≥ 0.16) |
| Fabric API | 0.152.2+26.2 | 0.116.14+1.21.1 |
| Gradle / Loom | Gradle 9.5, Loom 1.17-SNAPSHOT | Gradle 9.5.0, **Loom 1.17.16 (gepinnt, kein SNAPSHOT)** |
| HTTP-Server | Undertow 2.4 (eingebettet, relociert) | **JDK `com.sun.net.httpserver`** (keine Fremdabhängigkeit) |
| Frontend | TypeScript + Leaflet 1.9.4 (eigener Build) | **Eigenes Vanilla-JS-Canvas-Frontend** (keine Fremdbibliothek) |
| Tests | **keine** | JUnit 5 (Unit + HTTP-Integrationstests) |

## Lizenzanalyse

| Bestandteil | Lizenz | Bewertung für dieses Projekt |
| --- | --- | --- |
| Pl3xMap Core/Fabric/Bukkit | MIT (© 2020–2023 W. B. Galbreath) | Analyse und konzeptuelle Ableitung unproblematisch; Code wird dennoch **nicht** übernommen |
| Pl3xMap Webmap | MIT | dito |
| 9 Core-Dateien (Chunk-Parser, MCA-Mathematik, RegionModifiedState) | MIT, aus **BlueMap** übernommen (© Lukas Rieger) | Nicht kopieren; eigener MCA/NBT-Parser aus der öffentlichen Formatdokumentation |
| `BiomeManager.java` | **Dekompilierter Mojang-Code** („borrowed in good faith") | **Darf keinesfalls übernommen werden** |
| `grass.png` / `foliage.png` / `dry_foliage.png` Colormaps | **Mojang-Assets, im Repo mitgeliefert** | **Nicht mitliefern.** Eigene Lösung: Colormaps zur Laufzeit aus der Spiel-JAR des Nutzers extrahieren |
| Undertow | Apache-2.0 | Nicht benötigt (JDK-HTTP-Server) |
| cloud / adventure / caffeine / BlueNBT / lz4-java / SimpleYAML | MIT/Apache | Nicht benötigt |
| Leaflet 1.9.4 | BSD-2 | Nicht benötigt (eigenes Frontend) |
| Name/Branding „Pl3xMap" | Markenrechtlich geschützt | Eigener Name: **The Explorer's Friend** |

**Konsequenz:** Vollständige Neuimplementierung. Es werden keine Quellcodedateien, Assets,
Palettendaten oder Frontend-Dateien übernommen; die einzige Gemeinsamkeit ist die
Produktkategorie und öffentlich dokumentierte Dateiformate (Anvil/NBT, PNG, Blockstate-JSON).

## Architektur von Pl3xMap (Kurzfassung)

- **4 Gradle-Module**: `core` (Engine), dünne `bukkit`-/`fabric`-Schichten, `webmap`
  (TypeScript/Leaflet, als Java-Ressource verpackt und zur Laufzeit entpackt). Root-Task
  fusioniert alles in ein Uber-JAR („janky, but it works" laut eigenem Buildscript).
- **Rendering** liest ausschließlich gespeicherte `.mca`-Regionsdateien (BlueMap-Parser,
  versionsspezifische Chunk-Klassen 1.13–1.18+). 1 Region = 1 Kachel (512×512 px, 1 px = 1 Block).
  Ein Prozessor-Thread spiralisiert Regionen um den Spawn, Fan-out in einen ForkJoinPool.
- **Zoomstufen** werden im selben Task sofort per Read-Modify-Write in *geteilte* PNG-Dateien
  geschrieben — unter einer statischen, nie bereinigten Pfad-Lock-Map (größter IO-/Contention-Punkt).
- **Updates**: kein einziges Block-/Chunk-Event — mtime-Polling aller Regionsdateien alle 30 s
  gegen eine persistierte Timestamp-Map. Ein WatchService-Ansatz ist auskommentiert.
- **Blockfarben**: ~1200 Vanilla-Blöcke hartkodiert (`Blocks.java`), kuratierte `colors.yml`;
  Mod-Blöcke bekommen nur `defaultMapColor().col` (oft grau/unsichtbar). Indexgrenzen von
  2047 Blöcken/511 Biomen brechen große Modpacks (Issue #75). **Kein Texturscan.**
- **Web**: Undertow, SSE-Push + 1-s-Polling-Fallback, Settings-JSON wird jeden Tick neu erzeugt,
  fehlende Kacheln liefern leere 200-Antworten, keine Auth/Rate-Limits.

## Stärken von Pl3xMap

1. Rendern aus Regionsdateien: keine Chunkloads, kein Serverthread-Einfluss beim Vollrender.
2. 1 Region = 1 Kachel: einfache, robuste Kachelgeometrie (übernehme ich als Konzept).
3. Plattformübergreifendes Uber-JAR, kleines, schnelles Frontend, Marker-/Addon-Ökosystem.
4. Double-Buffered-Tile-Layer im Frontend (flickerfreies Aktualisieren).

## Schwächen / bekannte Probleme (Auswahl mit Belegen)

| Problem | Beleg |
| --- | --- |
| Fehlgeschlagener Region-Scan aktualisiert trotzdem den Zeitstempel → **permanente Kartenlöcher** | Issues #109, #126, #70 |
| Speicherlecks/OOM (Undertow, Browser, generell) | Issues #7 (gepinnt), #94, #115 |
| Mod-Blöcke ohne echte Farben; Indexgrenzen 2047/511 | Issue #75 |
| Versteckte Spieler-/Dimensionsdaten geleakt | Issue #142 |
| Folia-Crash durch async Zugriff auf Live-Serverzustand | Issue #138 |
| Busy-Wait `checkPaused()` pro Block; nicht-volatile Cross-Thread-Flags; `System.gc()` als Speicherpolitik; unbounded Ticket-Deque mit O(n)-Dedupe | Codeanalyse |
| Keinerlei automatisierte Tests | Codeanalyse |

## Vergleichsmatrix

| Bereich | Umsetzung in Pl3xMap | mögliche Schwäche | eigene Lösung |
| --- | --- | --- | --- |
| Modulstruktur | 4 Module + Uber-JAR-Merge | fragiler Merge-Task | Ein Fabric-Modul, klare Pakettrennung |
| Fabric-Integration | 2 Mixins + Netzwerk-Payloads | Client-Payload nötig für Komfort | 1 Mixin (Blockänderung), reine Server-Mod, kein Protokoll |
| Rendering-Quelle | Nur .mca von Disk | Aktualität an Autosave gebunden | Hybrid: Events + Live-Chunk-Snapshots (budgetiert) für Änderungen, eigener .mca-Reader für Vollrender |
| Blockfarben (Mods) | `defaultMapColor` | Mod-Blöcke grau/unsichtbar | **Automatischer Blockstate→Modell→Textur-Scan aller Mod-JARs** |
| Farbberechnung | kuratierte Paletten | Handpflege, keine Mod-Abdeckung | Alpha-gewichteter Mittelwert im linearen Farbraum + Ausreißer-Trim, algorithmusversionierter Cache |
| Biome-Tint | Mojang-PNGs im Repo (Lizenzproblem!) | Redistribution von Mojang-Assets | Colormaps zur Laufzeit aus Nutzer-Spiel-JAR extrahieren |
| Kachelspeicher | geteilte Zoom-PNGs, Read-Modify-Write unter Pfad-Locks | IO-Contention, Lock-Map wächst unbegrenzt | Kachel = eigene Datei je Zoomstufe; Parent wird aus 4 Kindern neu komponiert, Jobs dedupliziert |
| Updates | mtime-Polling (30 s) | Latenz, verpasste Fehler, Timestamp-Bug → Löcher | Mixin-Dirty-Events + Debounce + Max-Delay; Fehler behalten Dirty-Status (begrenzte Retries) |
| Scheduling | 1 Spiral-Thread + ForkJoinPool (halbe Cores) | globaler FJP-ähnlicher Fan-out, Busy-Wait-Pause | Eigene benannte, begrenzte Pools; Prioritätsqueue mit Merge; kooperative Pause |
| Caching | Timestamp-Map (.rms), Caffeine | Kein Inhalts-Hash, keine Schemaversionen | SHA-256-JAR-Inventar, inhaltsadressierter Texturfarbcache, Schema-/Algorithmusversionen überall |
| Webserver | Undertow (relociert eingebettet) | schwergewichtig, Leak-Issues | JDK-HTTP-Server, Limits, ETag, gzip, Traversal-Schutz |
| Frontend | Leaflet + TS-Build | Node-Build-Kette im Gradle | Kleines eigenes Canvas-Frontend, keine Abhängigkeiten |
| Konfiguration | SimpleYAML, viele Dateien | keine Validierungs-Fallbacks überall | Kommentiertes JSONC, feldweise Validierung mit sicheren Defaults |
| Befehle | cloud-Framework | zusätzliche Abhängigkeit | Brigadier/Fabric-Command-API direkt |
| API | Marker/Layer/Events, groß | breite Stabilitätsfläche | bewusst kleine API (Farbprovider + 2 Events) |
| Fehlerbehandlung | Scan-Fehler = stiller Kachelverlust | Karten-Löcher | Fehlerisolation pro Chunk/Kachel, Retry, WARN-Zusammenfassungen |
| Logging | verstreut | teils leise, teils Flut | Strukturierte `[ExplorersFriend/…]`-Kanäle, Rate-Limits, Zusammenfassungen |
| Tests | keine | Regressionen unbemerkt | JUnit-5-Suite für Kernlogik + HTTP |

## Eigene Verbesserungsvorschläge (bewertet)

| Vorschlag | Nutzen | Aufwand | Perf-Einfluss | Risiko | Entscheidung |
| --- | --- | --- | --- | --- | --- |
| Texturbasierte Auto-Farben für alle Mod-Blöcke | sehr hoch | hoch | Startup-Scan, gecacht | mittel | **MVP (Kernfeature)** |
| SHA-256-JAR-Inventar mit persistentem Cache | hoch | mittel | vernachlässigbar | gering | **MVP** |
| Event-getriebene Updates statt mtime-Polling | hoch | mittel | geringer als Polling | gering (1 Mixin) | **MVP** |
| Budgetiertes Live-Chunk-Snapshotting (µs-Budget/Tick) | hoch | mittel | kontrolliert | gering | **MVP** |
| Eigener .mca-Reader für Vollrender ohne Chunkloads | hoch | mittel | nur Worker-IO | mittel | **MVP** |
| Inhaltsadressierter Texturfarbcache + Algorithmusversion | hoch | gering | Startup ↓ | gering | **MVP** |
| Kachel-Priorisierung (Spielernähe > Änderungen > Backlog) | mittel | gering | positiv | gering | **MVP** |
| Fehler-Retry statt Timestamp-Löcher | hoch | gering | neutral | gering | **MVP** |
| Datenschutz: Positionsrundung, Invisible/Spectator-Filter | mittel | gering | neutral | gering | **MVP** |
| Leere Kacheln nicht schreiben | mittel | gering | IO ↓ | gering | **MVP** |
| Adaptive Workeranzahl nach MSPT | mittel | mittel | positiv | mittel | spätere Version |
| WebP mit PNG-Fallback | mittel | mittel | Speicher ↓ | mittel (Encoder-Dep) | spätere Version |
| Prometheus-Metriken / JSON-Diagnosebericht | mittel | gering–mittel | neutral | gering | teilweise: `/api/status` als JSON-Diagnose im MVP, Prometheus später |
| Marker-/Layer-API für Drittmods | mittel | mittel | neutral | Stabilitätszusagen | spätere Version (MVP: nur Farbprovider-API) |
| Zugriffstoken/Auth im Mod | mittel | mittel | neutral | Sicherheitsfläche | verworfen für MVP → Reverse-Proxy-Anleitung |
| Dynmap/BlueMap-Migrationstools | gering | hoch | – | hoch | verworfen |
| Content-Addressable Tile Storage (Dedupe identischer Kacheln) | gering–mittel | mittel | Speicher ↓ | mittel | verworfen für MVP (leere Kacheln entfallen bereits) |
| Watchdog für hängende Renderaufträge | mittel | gering | neutral | gering | **MVP** (Timeout + Logging) |
| Test-JARs mit absichtlich defekten Ressourcen | hoch (Testqualität) | gering | – | – | **MVP** (Testfixtures) |
