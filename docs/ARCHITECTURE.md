# Phase C – Architektur

> **Note (0.3.0):** the codebase has since been restructured into a Gradle
> multi-project — the Minecraft-free core lives in `common/`, thin per-version
> adapters in `platforms/fabric-*`, convention plugins in `buildSrc/` (see
> [MULTIVERSION.md](MULTIVERSION.md)). The component split, dataflow, threading and
> error-handling described below are unchanged; "Einzelmodul" refers to the original
> 0.1.0 layout.

Einzelmodul-Gradle-Projekt (bewusst gegen Überarchitektur entschieden); Trennung erfolgt
über Pakete mit klaren Abhängigkeitsrichtungen. Reine Logik (testbar ohne Minecraft) ist
strikt von dünnen Minecraft-Adaptern getrennt.

## Komponenten

```mermaid
graph TD
    subgraph Entry["bootstrap"]
        EF[ExplorersFriend<br/>ModInitializer]
    end
    subgraph Core["core"]
        MS[MapService<br/>Lifecycle + Wiring]
    end
    subgraph Scan["scan / resource / color"]
        JI[JarInventoryScanner<br/>SHA-256 + Cache]
        RI[ResourceIndex<br/>Mod-JARs + Vanilla-Assets]
        MR[ModelResolver<br/>Blockstate→Modell→Textur]
        CE[ColorExtractor<br/>linearer Farbraum]
        BCR[BlockColorRegistry<br/>stateId→ARGB]
    end
    subgraph World["world / region"]
        DT[DirtyTracker<br/>Debounce]
        CS[ChunkSnapshotter<br/>Tick-Budget]
        RR[RegionFileReader<br/>eigener Anvil/NBT-Parser]
    end
    subgraph Render["render"]
        RS[RenderScheduler<br/>Prio-Queue + Merge]
        TR[TileRenderer<br/>Relief + Wasser]
        TP[TilePyramid<br/>Zoom-Downscale]
        TS[TileStore<br/>atomare PNGs]
    end
    subgraph Web["web"]
        WS[MapHttpServer<br/>JDK HttpServer]
        UI[Web-UI<br/>Canvas, Vanilla JS]
    end
    CMD[commands /efmap]
    CFG[config JSONC]

    EF --> MS
    MS --> CFG
    MS --> JI --> RI --> MR --> CE --> BCR
    MS --> DT --> RS
    MS --> CS --> RS
    RS --> TR --> TS
    TR --> BCR
    RS --> RR
    TR --> TP --> TS
    MS --> WS
    WS --> TS
    WS --> UI
    CMD --> MS
```

## Datenfluss

```mermaid
flowchart LR
    A[Blockänderung<br/>Mixin] --> B[DirtyTracker<br/>Chunk-Set + Zeitstempel]
    A2[Chunk generiert<br/>Fabric-Event] --> B
    B -->|Debounce 5s / Max 60s| C[RenderScheduler]
    C -->|Chunk geladen| D[Snapshot-Anfrage<br/>Serverthread, µs-Budget]
    C -->|Chunk nicht geladen| E[RegionFileReader<br/>Worker, .mca von Disk]
    D --> F[TileRenderer]
    E --> F
    F --> G[TileStore<br/>PNG atomar]
    G --> H[TilePyramid<br/>Parent aus 4 Kindern]
    H --> G
    G --> I[HTTP /tiles/...]
    I --> J[Browser-Canvas]
```

## Threading-Modell

| Thread/Pool | Anzahl (Default) | Aufgaben | Blockierend erlaubt? |
| --- | --- | --- | --- |
| Server-Thread | – | Event-Marking (O(1)), Chunk-Snapshots unter µs-Budget, Command-Feedback | **nein** (hartes Budget) |
| `EF-Scan-#` | 2 | JAR-Hashing, Ressourcen-/Texturscan, Farbberechnung, Cache-IO | ja |
| `EF-Render-#` | 2 | Kachelrendern, PNG-Encode/IO, Zoompyramide, Regionsdatei-Parsing | ja |
| `EF-Sched` | 1 | Debounce-Timer, Fortschritts-Logs, Watchdog, Spieler-Snapshot | kurz |
| `EF-Web-#` | 2 | HTTP-Requests | ja (mit Timeouts) |

Regeln: Weltdaten werden **ausschließlich** auf dem Serverthread gelesen (Snapshots) oder
aus gespeicherten Dateien (Regionsreader). Worker sehen nur immutable Snapshots/Arrays.
Alle Pools sind benannt, daemonisiert, bounded und werden beim Shutdown geordnet beendet.
Kein globaler ForkJoinPool.

## Cachemodell

```mermaid
graph LR
    subgraph L1["Ebene 1: Identität"]
        A[jar-inventory.json<br/>SHA-256 je JAR]
    end
    subgraph L2["Ebene 2: Inhalt"]
        B[texture-colors.json<br/>sha256 PNG + AlgoVer → Farbe]
    end
    subgraph L3["Ebene 3: Ergebnis"]
        C[block-colors.json<br/>Block-ID → Farbe + Metadaten<br/>gültig für jarSetHash + AlgoVer]
    end
    subgraph L4["Ebene 4: Ausgabe"]
        D[tiles/*.png + meta.json<br/>Formatversion]
        E[HTTP ETag/304]
    end
    A -->|jarSetHash| C
    B --> C
    C --> D
    D --> E
```

## Lifecycle

```mermaid
sequenceDiagram
    participant L as Fabric Loader
    participant S as Server-Thread
    participant SC as EF-Scan
    participant R as EF-Render
    participant W as EF-Web
    L->>S: onInitialize (Events registrieren)
    S->>S: SERVER_STARTING: MapService.create (Config laden, Lock, Pools)
    S->>SC: SERVER_STARTED: startAsync()
    SC->>SC: JAR-Inventar (Cache-Abgleich)
    SC->>SC: Ressourcenscan (nur Neues), Farben auflösen
    SC->>W: Webserver starten
    SC->>R: RenderScheduler starten, Dirty-Backlog einplanen
    Note over S,R: Betrieb: Events→Dirty→Debounce→Snapshot/Region→Render→Tile
    S->>S: SERVER_STOPPING
    S->>R: Queue einfrieren, Worker beenden (Timeout 10 s)
    S->>W: HTTP-Server stoppen
    S->>SC: Scanner beenden, Caches/Fortschritt flushen
    S->>S: Lock freigeben
```

## Rendering-Pipeline (eine Kachel)

1. Eingabe: `TileSnapshot` — je Spalte (512×512 bzw. 16×16 pro Chunk): Oberflächenhöhe,
   oberster sichtbarer Blockzustand, Wassertiefe, Biom.
2. Farbe: `BlockColorRegistry` (stateId→ARGB, O(1)-Arrayzugriff), Tint (Gras/Laub/Wasser)
   per Biome-Colormap, manuelle Overrides bereits eingerechnet.
3. Schattierung: Relief aus Höhendifferenz zu West-/Nordnachbar; Wassertiefe dunkelt ab;
   transparente Blöcke werden über den darunterliegenden Untergrund geblendet.
4. PNG-Encode (ImageIO) → `TileStore.writeAtomic` → Parent-Kachel-Job (Merge) in Queue.
5. Fehler: Exception isoliert die Kachel (Retry ≤ 3), niemals die Queue.

## Webarchitektur

- JDK `HttpServer`, fester kleiner Threadpool, Verbindungslimit per Semaphore,
  Idle-Timeouts über `HttpServer`-Konfiguration + manuelle Request-Deadline.
- Routen: `/` + statische UI (nur eingebetteter Classpath, Whitelist), `/tiles/{dim}/{z}/{x}_{y}.png`
  (Regex-validiert, ETag aus mtime+Größe, `Cache-Control: no-cache` ⇒ 304-Revalidierung),
  `/api/status`, `/api/worlds`, `/api/players` (gzip, `no-store` für Spieler).
- Kein Zugriff auf Live-Serverzustand aus HTTP-Threads: Spielerliste wird vom
  `EF-Sched`-Thread periodisch auf dem Serverthread gesampelt und als immutabler
  JSON-Snapshot publiziert.

## Fehlerstrategie

| Fehler | Behandlung |
| --- | --- |
| Defektes JAR/PNG/JSON, Zyklen, fehlende Parents | Ressource überspringen, Fallbackfarbe, gebündeltes WARN, Scan läuft weiter |
| ZIP-Bomb-Indikatoren (Entries/Größe/Kante) | Datei überspringen + WARN |
| Kachel-Renderfehler | Retry ≤ 3, Dirty bleibt, Queue läuft weiter |
| Cache korrupt | Quarantäne `*.corrupt-N`, Neuaufbau |
| Port belegt / Bind-Fehler | Karte deaktiviert, Server läuft weiter (ERROR-Log) |
| Voller Datenträger | atomare Writes verhindern Teilzustände; Fehler gebündelt geloggt |
| Worker-Crash | Uncaught-Handler loggt; Watchdog erkennt hängende Jobs (Timeout) |
| Zweiter Serverstart auf denselben Daten | Lock verhindert Schreibkonflikte (Read-Only-Modus) |

## Shutdown-Reihenfolge

1. Dirty-/Progress-Zustand persistieren (best effort, atomar)
2. Scheduler-Thread stoppen (keine neuen Jobs)
3. Render-Queue einfrieren; Worker `shutdown()` → `awaitTermination(10 s)` → `shutdownNow()`
4. HTTP-Server `stop(1 s)`
5. Scan-Pool beenden; Caches flushen
6. Datei-Lock freigeben

Reihenfolge stellt sicher: keine neuen Aufträge nach Persistenzbeginn, keine
halbgeschriebenen Kacheln (atomare Writes), JVM-Exit nie durch Non-Daemon-Threads blockiert.
