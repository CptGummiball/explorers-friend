# Phase B – Spezifikation (MVP 0.1.0)

> **Note (0.3.0):** historical MVP specification. Several "Nicht-Ziele" have since
> shipped: overlays (claims/markers/players), Prometheus `/metrics` and multi-version
> support for Minecraft 1.21.1 – 26.2 arrived in 0.2.0/0.3.0 — see
> [CHANGELOG.md](../CHANGELOG.md) and [MULTIVERSION.md](MULTIVERSION.md).

## Produkt

**The Explorer's Friend** (`explorersfriend`) — eine vollständig serverseitige,
browserbasierte Weltkarte für Fabric-Server (Minecraft 1.21.1, Java 21). Spieler
benötigen keine Clientinstallation.

## MVP-Funktionsumfang

1. **JAR-Inventar**: Bei jedem Start werden alle vom Fabric Loader geladenen Mods samt
   Ursprungsdateien (inkl. Nested JARs) inventarisiert: Mod-ID, Version, Dateiname, Größe,
   mtime, SHA-256. Persistenter Cache; unveränderte Inhalte werden nicht erneut analysiert;
   identische Inhalte (gleicher Hash, anderer Name) werden dedupliziert; entfernte
   Versionen werden bereinigt.
2. **Ressourcen-/Farbscan**: Blockstate → Modellkette (mit Parents, Texturvariablen,
   Zyklenerkennung) → Top-Textur-Präferenz → repräsentative Farbe (alpha-gewichteter
   Mittelwert im linearen Farbraum, deterministischer Ausreißer-Trim, `.mcmeta`-bewusst).
   Vanilla-Assets: im Einzelspieler aus der laufenden Spiel-JAR; auf Dedicated Servern
   optionaler, SHA-1-verifizierter Download der offiziellen Client-JAR von Mojang
   (abschaltbar; Fallback: eingebaute Minimalpalette + WARN).
3. **Biome-Tint**: Gras/Laub über die zur Laufzeit extrahierten Vanilla-Colormaps
   (Temperatur/Niederschlag aus der Biome-Registry), Wasser über `BiomeEffects.waterColor`.
4. **Rendering**: Topografische 2D-Karte. 1 Kachel = 512×512 px = 1 Region (1 px = 1 Block)
   auf Zoomstufe 0; konfigurierbare Zoom-Out-Pyramide (Standard 4 Stufen). Höhenrelief-
   und Wassertiefen-Schattierung. Leere Kacheln werden nicht geschrieben.
5. **Aktualisierung**: Blockänderungen (1 Mixin) und Chunkgenerierung markieren Kacheln
   dirty; Debounce (Standard 5 s) + Max-Delay (60 s); Live-Chunk-Snapshots auf dem
   Serverthread mit hartem µs-Budget; Rendern/IO ausschließlich auf Worker-Threads.
6. **Vollrender**: `/efmap render <Welt> [Radius]` liest Regionsdateien mit eigenem
   Anvil/NBT-Parser off-thread (keine Chunkloads); Fortschritt persistent, abbrech- und
   wiederaufnehmbar; pausierbar.
7. **Webserver**: Eingebetteter JDK-HTTP-Server. Statisches eigenes Frontend
   (Canvas, Vanilla JS), Kachel-Endpunkt, `/api/status`, `/api/worlds`, `/api/players`.
   gzip, ETag/304, Cache-Control, Path-Traversal-Schutz, Verbindungs-/Größenlimits.
8. **Konfiguration**: Kommentiertes JSONC, feldweise Validierung mit sicheren Defaults;
   manuelle Blockfarben (`block-colors.jsonc`) haben immer Vorrang.
9. **Befehle**: `/efmap status|render|update|pause|resume|cancel|reload|scan …|colors reload|cache …|web status`
   mit Berechtigungsstufe 2+, asynchroner Fortschrittsmeldung.
10. **Beobachtbarkeit**: strukturierte Logkanäle `[ExplorersFriend/Scanner|Colors|Renderer|Cache|Web]`,
    gebündelte Fortschritts- und Abschlusszusammenfassungen, Rate-Limits.
11. **API (minimal)**: `BlockColorProvider`-Registrierung + Callbacks „Scan abgeschlossen"
    und „Kachel gerendert". Keine weitergehenden Stabilitätszusagen in 0.1.x.

## Nicht-Ziele (0.1.0)

- Kein Client-Zusatzfeature (In-Game-Map, Minimap), keine Netzwerk-Payloads.
- Keine 3D-/Isometrie-Ansicht, keine Nacht-/Biome-/Spezial-Layer.
- Keine Marker-API für Drittmods (nur interne Spielermarker), keine Claims-Integrationen.
- Keine eingebaute Authentifizierung (dokumentierter Reverse-Proxy-Weg).
- Keine serverseitigen Resource-Pack-Überschreibungen (dokumentierte Einschränkung).
- Kein WebP, kein Prometheus-Endpunkt.
- Keine Unterstützung für andere MC-Versionen als 1.21.1.

## Akzeptanzkriterien

Identisch mit den verbindlichen Kriterien der Aufgabenstellung; zusätzlich:

- `./gradlew build` und `./gradlew test` enden erfolgreich (Java 21).
- Dedicated-Server-Headless-Start: Mod initialisiert, scannt, startet Webserver, Karte im
  Browser abrufbar; zweiter Start meldet Cache-Treffer und überspringt unveränderte JARs.
- Test-Mod mit absichtlich defekten Ressourcen (ungültiges JSON, zyklische Parents,
  defektes PNG) bricht weder Scan noch Serverstart.

## Leistungsziele (Messgrößen)

| Messgröße | Ziel |
| --- | --- |
| Serverthread-Belastung durch die Mod pro Tick (Snapshots) | ≤ 1,5 ms Budget (konfigurierbar), hartes Chunk-Limit/Tick |
| Renderzeit pro Regionskachel (512×512, Worker) | < 250 ms Durchschnitt auf moderner Hardware |
| Zusätzlicher RAM-Bedarf im Leerlauf | < 64 MB (keine Weltkopien, keine unbounded Queues) |
| Startverzögerung bei unverändertem Modset (2. Start) | < 2 s zusätzlich (nur Inventarabgleich) |
| Warteschlange | hart begrenzt (Default 4096 Kacheljobs), Merge statt Duplikate |
| HTTP-Threads | 2 (Default), Verbindungslimit 64 |

Messmethode: eingebaute Zähler/Timer (Status-Endpunkt + Logzusammenfassungen) sowie
JFR-Läufe bei der Verifikation.

## Sicherheitsgrenzen

- Bind-Default `127.0.0.1`; öffentliche Exposition ist eine bewusste Konfigurationsentscheidung.
- Keine Dateipfade aus URL-Parametern; Kachelpfade werden ausschließlich aus validierten
  Zahlen/Identifier-Regexen zusammengesetzt; statische Dateien nur aus dem eingebetteten
  Classpath-Verzeichnis.
- ZIP-Schutz: Entry-Anzahl-Limit, Dekompressionsgrößen-Limit, Texturkantenlimit,
  Pfadnormalisierung (kein `..`), keine Symlink-Verfolgung.
- Spielerdaten: nur Name, UUID (für Avatar), gerundete Position, Dimension; Invisible/
  Spectator-Filter; komplett abschaltbar.
- Downloads: ausschließlich Mojang-Client-JAR von `piston-meta/piston-data.mojang.com`,
  SHA-1-verifiziert, abschaltbar.

## Datenformate

Alle persistierten Formate tragen `schemaVersion`; Farbdaten zusätzlich `algorithmVersion`.

```
<server>/explorersfriend/
├── cache/
│   ├── jar-inventory.json      # Schema 1: JAR-Identitäten (SHA-256)
│   ├── texture-colors.json     # Schema 1: sha256(Textur) + algoVersion → Farbe (inhaltsadressiert)
│   ├── block-colors.json       # Schema 1: Block-ID → Farbe + Metadaten (Quelle, Modell, Textur, Grund, Zeit)
│   ├── vanilla-client.jar      # optionaler Mojang-Download (SHA-1-geprüft)
│   └── render-progress/<dim>.json  # wiederaufnehmbarer Vollrender-Fortschritt
├── tiles/<dimension>/<zoom>/<x>_<z>.png
└── tiles/<dimension>/meta.json # Kachelformat-Version, Basiszoom
config/explorersfriend/config.jsonc
config/explorersfriend/block-colors.jsonc   # manuelle Überschreibungen (höchste Priorität)
```

Dimensionsnamen werden als Dateisystem-sichere Slugs kodiert (`minecraft_overworld`).

## Cache- und Invalidierungsregeln

1. **JAR-Ebene**: Identität = SHA-256. Fast-Path: (Pfad, Größe, mtime) unverändert ⇒ Hash
   aus Cache übernommen. Namensänderung bei gleichem Inhalt ⇒ Dedupe, kein Rescan.
2. **Texturfarben**: Schlüssel = `sha256(PNG-Bytes) + algorithmVersion + Animationsmodus`.
   Identische Texturen aus mehreren JARs werden genau einmal analysiert.
3. **Blockfarben**: gültig, solange `jarSetHash` (Hash der sortierten JAR-Hashes) und
   `algorithmVersion` übereinstimmen; sonst Neuaufbau der Zuordnung unter Wiederverwendung
   des Texturfarbcaches.
4. **Kacheln**: `meta.json`-Formatversion weicht ab ⇒ Welt wird zum Neurender eingeplant.
   Fehlgeschlagene Kachel behält Dirty-Status (max. 3 automatische Retries, dann WARN).
5. **Korruption**: defekte Cachedateien werden als `*.corrupt-N` beiseitegelegt und neu
   aufgebaut; niemals Serverstart-Abbruch.
6. **Gleichzeitige Starts**: exklusives Lock (`cache/.lock`, `FileChannel.tryLock`);
   ohne Lock läuft die Mod im Read-Only-Kartenmodus mit deutlicher Warnung.
7. Alle Cache-Schreibvorgänge sind atomar (Temp-Datei + `ATOMIC_MOVE`).
