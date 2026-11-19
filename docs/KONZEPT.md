# open2ndbrain – Konzept

> Ein „Second Brain“ für Android: hört 24/7 zu (auch im Hintergrund, auch über Bluetooth-Mikrofone),
> transkribiert alles **auf dem Gerät** und macht es durchsuchbar – wie OMI AI, aber **vollständig offline**.

---

## 1. Ziele & Nicht-Ziele

**Ziele**
1. **Ein Schalter.** App öffnen → großer Start/Stopp-Button. Mehr Bedienung braucht der Alltag nicht.
2. **Durchgehende Aufnahme** als Foreground-Service mit dauerhafter Benachrichtigung (Pause / Stopp direkt dort).
3. **Stündliche Dateien**, an der Uhrzeit ausgerichtet (`2026-11-19_14-00.ogg` = 14:00–15:00), lückenloser Wechsel.
4. **Bluetooth-Mikrofone** (Headset, Ohrhörer, Lavalier) mit automatischem Wechsel und Rückfall aufs interne Mikro.
5. **Automatische Transkription auf dem Gerät** (Standard: NVIDIA Parakeet TDT 0.6B v3, multilingual, sehr gut für Deutsch).
6. **Durchsuchbarer Index**: Volltext sofort, später semantische Suche („Was hat Max über den Umzug gesagt?“).
7. **Lokales KI-Modell** (Phase 4): Tageszusammenfassungen, To-dos, Fragen an das eigene Gedächtnis.
8. **Datenschutz by Design**: Keine Cloud, kein Tracking. Internetzugriff nur für den Modell-Download.

**Nicht-Ziele (vorerst)**
- Keine Cloud-Synchronisation, kein Konto, kein Server.
- Keine Live-Untertitel in Echtzeit (Transkription läuft leicht verzögert im Hintergrund – das spart Akku).
- Kein iOS.

---

## 2. Bedienkonzept (UI)

Drei Bereiche über eine untere Navigationsleiste:

| Tab | Inhalt |
|---|---|
| **Aufnahme** | Riesiger Start/Stopp-Button, aktuelles Mikrofon (z. B. „Pixel Buds“), Pegelanzeige, heutige Aufnahmedauer, Speicherplatz, Status der Transkriptions-Warteschlange |
| **Zeitleiste** | Tage → Stunden → Sprachsegmente. Antippen = Text lesen und ab genau dieser Stelle abspielen. Tageszusammenfassung oben (ab Phase 4) |
| **Suche / Fragen** | Ein Suchfeld. Treffer mit Zeitstempel, Kontext und Abspielknopf. Ab Phase 5 Umschalter „Fragen“ (Chat mit dem eigenen Gedächtnis) |

Dazu **Einstellungen** (Zahnrad): Modelle, Bevorzugtes Mikrofon, Aufbewahrungsdauer, Transkription nur beim Laden ja/nein, Export.

**Benachrichtigung** (immer sichtbar, während aufgenommen wird): „Nimmt auf · 3 h 12 min · Pixel Buds“ mit den Aktionen **Pause 15 min**, **Pause**, **Stopp**.
Zusätzlich eine **Schnelleinstellungs-Kachel** und ein **Homescreen-Widget** für Start/Pause.

---

## 3. Architektur

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose)  Aufnahme · Zeitleiste · Suche · Einstellungen   │
└───────────────▲───────────────────────────────▲──────────────────────┘
                │ StateFlow                       │ Room-Queries
┌───────────────┴───────────────┐   ┌─────────────┴───────────────────┐
│ RecorderService (FGS          │   │ Index (Room/SQLite)              │
│ type=microphone)              │   │ recordings · segments · FTS      │
│  AudioRecord 16 kHz mono PCM  │   │ embeddings · summaries           │
│  ├─ AudioRouter (BT/intern)   │   └─────────────▲───────────────────┘
│  ├─ VAD (Silero) → Sprachmarken│                │
│  └─ HourlyWriter → Opus/OGG   │   ┌─────────────┴───────────────────┐
└───────────────┬───────────────┘   │ Pipeline (WorkManager)           │
                │ Datei fertig      │  1. Transkription (sherpa-onnx)  │
                └──────────────────▶│  2. Embeddings (Phase 4)         │
                                    │  3. LLM-Zusammenfassung (Ph. 4)  │
                                    └─────────────────────────────────┘
                     ModelManager: Download, Prüfsumme, Auswahl der Modelle
```

**Technik-Stack**
- Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, Hilt (DI), Room, WorkManager, DataStore
- **sherpa-onnx** (Apache-2.0) für Spracherkennung, VAD und später Sprechererkennung – liefert fertige Android/Kotlin-Bindings und unterstützt Parakeet-Modelle
- **llama.cpp** (MIT) per JNI für das lokale Sprachmodell (Phase 4)
- minSdk **29** (Android 10: Opus-Encoder + OGG-Muxer im System, Foreground-Service-Typ „microphone“), targetSdk 35
- ABI: `arm64-v8a` (Modelle sind ohnehin nur auf 64-Bit-Geräten sinnvoll)

> **Lizenz-Hinweis:** sherpa-onnx (Apache-2.0) und llama.cpp (MIT) sind freizügig lizenziert.
> Die Lizenz von open2ndbrain kannst du daher frei wählen.

---

## 4. Aufnahme-Kern (das Herzstück)

### 4.1 Foreground-Service
- `RecorderService` mit `foregroundServiceType="microphone"`, Berechtigungen `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`.
- Ein `AudioRecord` liest dauerhaft 16 kHz / mono / 16 bit (genau das Format, das die Modelle erwarten → kein Resampling).
- `START_STICKY` + Watchdog: Stirbt der Prozess, wird neu gestartet und die angefangene Stunde in einer neuen Teildatei fortgesetzt.
- Bei der Einrichtung fragt die App nach der **Ausnahme von der Akku-Optimierung** und zeigt herstellerspezifische Tipps
  (Samsung, Xiaomi, Huawei usw. beenden Hintergrund-Apps aggressiv).

**Ehrliche Einschränkung von Android 14/15:** Ein Mikrofon-Foreground-Service darf *nicht* aus dem Hintergrund
oder direkt nach dem Neustart (`BOOT_COMPLETED`) gestartet werden. Nach einem Neustart zeigt die App deshalb eine
Benachrichtigung „Aufnahme fortsetzen“ – **ein Antippen genügt**. Solange der Dienst läuft, ist er stabil; das
genaue Neustartverhalten nach einem Absturz wird in Phase 1 auf echten Geräten getestet.

### 4.2 Stündliche Aufteilung
- Der `HourlyWriter` kodiert den PCM-Strom mit dem System-Opus-Encoder (`MediaCodec`) in OGG (`MediaMuxer`).
- Zur vollen Stunde wird der Encoder auf eine neue Datei umgeschaltet, **ohne dass `AudioRecord` angehalten wird** → keine Lücke.
- Dateinamen: `audio/2026/11/19/2026-11-19_14-00.ogg`; bei Unterbrechungen `…_14-00_b.ogg`.
- Neben jeder Audiodatei liegt eine kleine `.json` mit Mikrofonwechseln, Pausen und den VAD-Sprachabschnitten.

**Speicherbedarf (Opus 16 kbit/s, Sprachqualität):** ca. 7 MB pro Stunde → ca. 170 MB pro Tag → ca. 5 GB pro Monat.
Optionen:
- **„Stille verwerfen“** (Standard: an): Die VAD erkennt Sprache; Abschnitte ohne Sprache werden nicht gespeichert.
  Realistisch sinkt der Bedarf dadurch auf 20–40 %.
- **Aufbewahrung**: Audio nach X Tagen löschen (z. B. 30), **Transkripte bleiben für immer**. Einzelne Stellen können markiert („📌 behalten“) werden.

### 4.3 Bluetooth-Mikrofone
- `AudioRouter` beobachtet die Geräte per `AudioDeviceCallback`.
- Android 12+: `AudioManager.setCommunicationDevice()` für BT-SCO-/LE-Audio-Headsets; Android 10/11: `startBluetoothSco()`.
- `AudioRecord.setPreferredDevice()` wählt das Mikrofon. Verbindet sich ein BT-Mikro → automatischer Wechsel,
  Verbindung weg → Rückfall aufs interne Mikro **ohne Unterbrechung der Aufnahme**.
- Hinweis: Klassisches Bluetooth-Headset-Audio (HFP) liefert 8 kHz (schmalbandig) oder 16 kHz (mSBC, „HD Voice“).
  16 kHz reicht für die Transkription. LE-Audio-Geräte sind besser.
- Einstellung „Bevorzugtes Mikrofon“: Automatisch / Immer intern / Bestimmtes Gerät.
- Nebeneffekt von SCO: Während ein BT-Mikrofon aktiv ist, läuft Musik über das Headset nur in Telefonqualität.
  Das wird in der UI erklärt; wer das nicht will, wählt „Immer intern“.

### 4.4 Telefonate & Konflikte
- Andere Apps (Telefon, WhatsApp-Anruf, Diktat) können das Mikrofon belegen. Android meldet das per
  `AudioRecordingCallback` → die App kennzeichnet die Lücke („Mikrofon von anderer App belegt“) und setzt danach automatisch fort.
- Telefonate selbst werden von Android grundsätzlich nicht mitgeschnitten (Systemvorgabe).

---

## 5. Transkription auf dem Gerät

### 5.1 Ablauf
1. Ist eine Stundendatei abgeschlossen, legt die App einen Auftrag in WorkManager an.
2. Der Worker dekodiert die Datei und schneidet sie anhand der **VAD-Sprachabschnitte** (Silero VAD) in Segmente von max. ~30 s.
3. Jedes Segment wird mit **sherpa-onnx + Parakeet TDT 0.6B v3 (int8)** transkribiert – inkl. Satzzeichen, Groß-/Kleinschreibung und Zeitstempeln.
4. Ergebnisse landen als Segmente (Start/Ende, Text, Mikrofon) in der Datenbank → sofort durchsuchbar.

**Wann wird transkribiert?** Einstellbar:
- *Sofort* (nach jeder Stunde) – Standard bei modernen Geräten
- *Nur beim Laden* – schont den Akku, Ergebnisse dann über Nacht

Optional („Schnellmodus“): ein kleines Streaming-Modell transkribiert live mit, Parakeet ersetzt den Text später in besserer Qualität.

### 5.2 Modelle (ModelManager)
Modelle sind **nicht in der APK** (zu groß), sondern werden in der App einmalig heruntergeladen (mit Prüfsumme, fortsetzbar).
Danach braucht die App kein Internet mehr. Eigene Modelldateien können auch manuell importiert werden.

| Zweck | Standardmodell | Größe ca. | Alternative |
|---|---|---|---|
| Spracherkennung | Parakeet TDT 0.6B v3 int8 (multilingual) | ~650 MB | Whisper small/turbo (mehr Sprachen, langsamer) |
| Sprachaktivität (VAD) | Silero VAD | 2 MB | – |
| Semantische Suche (Ph. 4) | multilingual-e5-small (ONNX int8) | ~120 MB | – |
| Sprecher (Ph. 5) | 3D-Speaker / pyannote-Segmentierung | ~30 MB | – |
| Sprachmodell (Ph. 4) | Qwen3 1.7B oder Gemma 3 1B, GGUF Q4 | 1–1,2 GB | Gemma 3 4B / Qwen3 4B auf starken Geräten (~2,5 GB) |

> Die genaue Geschwindigkeit (Echtzeitfaktor) messen wir in Phase 2 auf deinem Gerät. Erwartung für ein aktuelles
> Mittel-/Oberklasse-Gerät: deutlich schneller als Echtzeit; da typischerweise nur 2–5 h pro Tag tatsächlich
> gesprochen wird, ist die Last gut machbar.

---

## 6. Second-Brain-Index

### 6.1 Datenmodell (Room)
```
recordings  (id, start, end, file, mic, status)
segments    (id, recording_id, start_ms, end_ms, text, speaker_id?, pinned)
segments_fts  – FTS4-Volltextindex über segments.text (Umlaut-tolerant, Präfixsuche)
embeddings  (segment_chunk_id, vector BLOB)            – Phase 4
day_notes   (date, summary, todos, topics, people)      – Phase 4
speakers    (id, name, voice_embedding)                 – Phase 5
```

### 6.2 Suche
- **Phase 3 – Volltext:** Sofortige Suche mit Hervorhebung, Filter nach Datum/Zeitraum, Treffer → Abspielen ab Zeitstempel.
- **Phase 4 – Semantisch:** Segmente werden zu ~1-Minuten-Abschnitten zusammengefasst und eingebettet. Suche kombiniert
  Volltext- und Vektortreffer (hybride Suche). Bei einigen 10 000 Abschnitten pro Jahr reicht eine einfache
  Kosinus-Suche im Speicher – keine zusätzliche Vektordatenbank nötig.

### 6.3 Export (Offenheit)
- Export als **Markdown**, ein Dokument pro Tag (`2026-11-19.md`), kompatibel mit **Obsidian**-Vaults.
- Vollständiges Backup (DB + Audio) als ZIP.

---

## 7. Lokale KI (Phase 4/5)

Läuft nachts beim Laden (oder auf Knopfdruck), komplett auf dem Gerät mit llama.cpp:
- **Tageszusammenfassung**: Was war heute? Wichtige Gespräche, Entscheidungen.
- **Automatische To-dos & Termine** („Ich ruf dich Freitag an“ → To-do mit Verweis auf die Stelle).
- **Themen & Personen** als Schlagworte → Filter in der Zeitleiste.
- **Fragen an das Gedächtnis (RAG)**: Frage → hybride Suche holt passende Abschnitte → kleines LLM antwortet
  **mit Quellenangaben** (antippbar, springt zur Aufnahme). Das verhindert, dass das kleine Modell „halluziniert“.

Kleine Modelle (1–4 B Parameter) sind für Zusammenfassen und Extrahieren gut geeignet, aber kein Ersatz für große Cloud-Modelle.
Deshalb bleibt die Suche das Fundament und die KI ist ein Aufsatz.

---

## 8. Datenschutz & Recht

- Alle Daten bleiben im app-privaten Speicher. Keine Analytics, keine Absturzberichte nach außen.
- Netzwerkzugriff ausschließlich für den Modell-Download (in der UI klar ausgewiesen). Optional später ein Build-Flavor
  „Airgap“ ganz ohne Internetberechtigung (Modelle per Datei-Import).
- Optionale **Verschlüsselung** von Datenbank und Audio (SQLCipher / Jetpack Security), entsperrt per Fingerabdruck (Phase 5).
- **Rechtlicher Hinweis:** In Deutschland ist das Aufnehmen des *nicht öffentlich gesprochenen Wortes anderer*
  ohne deren Einverständnis strafbar (§ 201 StGB). Die App ist als persönliches Gedächtnis gedacht. Deshalb:
  - beim Einrichten ein deutlicher Hinweis,
  - **Pause mit einem Fingertipp** (Benachrichtigung, Kachel, Widget, optional Schütteln),
  - optional „Nur meine Stimme behalten“ (Phase 5, über Sprechererkennung werden fremde Stimmen verworfen).

---

## 9. Umsetzungsplan

| Phase | Inhalt | Ergebnis |
|---|---|---|
| **1 – Recorder** | Projektgerüst, Foreground-Service, AudioRecord, stündliche Opus-Dateien, VAD-„Stille verwerfen“, BT-Routing, Benachrichtigung, Kachel, Akku-Einrichtungsassistent, einfache Liste der Aufnahmen mit Player | Zuverlässige 24/7-Aufnahme |
| **2 – Transkription** | ModelManager (Download/Import), sherpa-onnx-Einbindung, Parakeet v3, Worker-Pipeline, Lade-Einstellung, Benchmark-Anzeige | Automatische Transkripte |
| **3 – Index & Suche** | Room + FTS, Zeitleiste, Volltextsuche, Abspielen ab Zeitstempel, Aufbewahrungsregeln, Markdown-Export | Durchsuchbares Second Brain |
| **4 – Semantik & KI** | Embeddings, hybride Suche, llama.cpp, Tageszusammenfassung, To-dos | „OMI, aber offline“ |
| **5 – Komfort** | Fragen/Chat mit Quellen, Sprechererkennung & -namen, Verschlüsselung, Widget, Obsidian-Sync-Ordner | Ausbau |

**Build & Verteilung**
- GitHub Actions: Bei jedem Push auf den Entwicklungszweig wird eine Debug-APK gebaut (inkl. Unit-Tests/Lint).
- Upload per FTP nach `https://dev.danerd.de/open2ndbrain/` (Secrets `FTP_SERVER`, `FTP_USER`, `FTP_PASSWORD`).
  Dort eine kleine `index.php` (PHP 8.1, ohne Datenbank), die die vorhandenen APKs mit Datum/Version zum Download auflistet –
  so installierst du neue Testversionen direkt am Handy.
- Für Release-Builds später ein Signaturschlüssel als GitHub-Secret (von dir angelegt).

---

## 10. Offene Entscheidungen

1. **Zielgerät(e):** Welches Handy nutzt du (Hersteller/Modell, Android-Version)? Das bestimmt Akku-Tipps und Modellgröße.
2. **Stille verwerfen** als Standard – oder lieber wirklich alles speichern?
3. **Aufbewahrung Audio:** Standard 30 Tage, Transkripte unbegrenzt – passt das?
4. **Lizenz** des Projekts (z. B. GPL-3.0, MIT oder Apache-2.0)?
5. **App-Name/Paket:** Vorschlag `open2ndbrain`, Paket `de.danerd.open2ndbrain`.
