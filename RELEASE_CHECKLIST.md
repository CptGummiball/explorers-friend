# Release-Checkliste (manueller Upload)

Stand: v0.2.1 — Repository und JAR sind vorbereitet, die folgenden Schritte machst du
manuell. Angenommener Repo-Name: `explorers-friend` unter `CptGummiball` — wenn du
einen anderen Namen wählst, vorher die URLs in `src/main/resources/fabric.mod.json`
(`contact`), `README.md` und `docs/MODRINTH_DESCRIPTION.md` anpassen und neu bauen.

## 1. GitHub

Das lokale Repository ist initialisiert (Branch `main`, Initial-Commit, Tag `v0.2.1`).

1. Neues Repository auf GitHub anlegen: **CptGummiball/explorers-friend**
   (öffentlich, ohne README/License/gitignore — alles ist schon lokal vorhanden).
2. Push:
   ```bash
   git remote add origin https://github.com/CptGummiball/explorers-friend.git
   git push -u origin main --tags
   ```
3. Der Actions-Workflow (`.github/workflows/build.yml`) baut und testet automatisch
   bei jedem Push (JDK 21, `gradlew clean test check build`) und hängt die JAR als
   Artefakt an.
4. GitHub-Release anlegen: Releases → "Draft a new release" → Tag `v0.2.1` wählen →
   Titel `The Explorer's Friend 0.2.1` → als Beschreibung den 0.2.1- und 0.2.0-Abschnitt
   aus [CHANGELOG.md](CHANGELOG.md) einfügen → **`build/libs/explorersfriend-0.2.1.jar`**
   als Datei anhängen (optional zusätzlich die `-sources.jar`).

## 2. Modrinth

1. Neues Projekt anlegen (<https://modrinth.com/create/project>):
   - **Name:** The Explorer's Friend
   - **Slug-Vorschlag:** `explorers-friend` (muss auf Modrinth frei sein)
   - **Summary:** "Lightweight, fully server-side browser world map — auto-detects
     block colors of every installed mod. Players install nothing."
   - **Kategorien:** `utility`, `management` (optional zusätzlich `multiplayer`)
   - **Environment:** Client **unsupported/optional**, Server **required**
     (Modrinth-Setting: "server side: required", "client side: unsupported" —
     die Mod läuft im Client zwar fehlerfrei, bringt dort aber nur im
     Einzelspieler-/LAN-Betrieb etwas; alternativ "optional" wählen)
   - **Lizenz:** MIT
   - **Links:** Source + Issues auf das GitHub-Repo setzen
2. **Beschreibung:** kompletten Inhalt von
   [docs/MODRINTH_DESCRIPTION.md](docs/MODRINTH_DESCRIPTION.md) einfügen
   (ist ohne repo-relative Links geschrieben, funktioniert 1:1 auf Modrinth).
3. **Icon:** `src/main/resources/assets/explorersfriend/icon.png` hochladen (128×128).
4. **Version anlegen:**
   - Datei: `build/libs/explorersfriend-0.2.1.jar`
   - Versionsnummer: `0.2.1` · Typ: **Beta** (erste öffentliche Version; ab
     ausreichend Praxis-Feedback auf Release umstellen)
   - Game Version: **1.21.1** · Loader: **Fabric**
   - Changelog: 0.2.1 + 0.2.0 + 0.1.0-Abschnitte aus [CHANGELOG.md](CHANGELOG.md)
   - **Dependencies:** Fabric API → *required*; FTB Chunks → *optional*;
     Open Parties and Claims → *optional*
5. **Galerie (optional, empfohlen):** Es gibt noch keine Screenshots — dafür einmal
   lokal einen Server mit der Mod starten, die Karte im Browser öffnen (Overworld
   mit gerenderten Kacheln, Ebenen-Panel offen, ggf. ein Banner-Marker) und 1–3
   Screenshots hochladen.

## 3. Nach dem Upload

- GitHub-Repo: unter Settings → "Issues" aktiviert lassen (fabric.mod.json verweist
  darauf).
- Modrinth-Projekt-URL in `gradle.properties`/README ergänzen ist nicht nötig,
  kann aber als Link im README-Kopf nachgetragen werden.
- Bei zukünftigen Versionen: `mod_version` in `gradle.properties` erhöhen,
  CHANGELOG ergänzen, `./gradlew clean test check build`, taggen (`git tag vX.Y.Z`),
  pushen, JAR auf beiden Plattformen hochladen.

## Datei-Hashes (zur Kontrolle nach dem Upload)

Modrinth zeigt nach dem Upload SHA-1/SHA-512 an; lokal prüfbar mit:
```powershell
Get-FileHash build\libs\explorersfriend-0.2.1.jar -Algorithm SHA512
```
