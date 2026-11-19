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
- **Kompatibilität: alle modernen Android-Versionen, Android 10 bis 16.** minSdk **29** (ab Android 10 sind Opus-Encoder,
  OGG-Muxer und der Vordergrunddienst-Typ „microphone“ im System enthalten), targetSdk/compileSdk **36** (Android 16).
  Die Sonderregeln von Android 11 (Mikrofon nur „während der Nutzung“), 12 (Startverbote aus dem Hintergrund),
  13 (Benachrichtigungs-Berechtigung) und 14/15 (Vordergrunddienst-Typen) sind eingeplant (siehe 4.1).
- ABI: `arm64-v8a` (Modelle sind ohnehin nur auf 64-Bit-Geräten sinnvoll)

### Lizenz: GNU AGPL-3.0-or-later
Rechtlich passt das. Alle eingebundenen Bibliotheken dürfen mit der AGPL-3.0 kombiniert werden
(keine Rechtsberatung, aber das entspricht der gängigen Einschätzung der FSF):

| Komponente | Lizenz | Verträglich mit AGPL-3.0 |
|---|---|---|
| sherpa-onnx, AndroidX/Jetpack, Kotlin | Apache-2.0 | ja (Apache-2.0 ist mit (A)GPL**v3** verträglich) |
| ONNX Runtime, llama.cpp, Silero VAD | MIT | ja |
| Parakeet TDT v3 (Modell) | CC-BY-4.0 | ja; wird nicht mitgeliefert, sondern geladen → Namensnennung in „Über/Lizenzen“ |
| Qwen3 (Modell) | Apache-2.0 | ja; wird geladen |
| Gemma (Modell, optional) | Gemma-Nutzungsbedingungen | nicht im Repo/APK; der Nutzer lädt es selbst und stimmt beim Download zu |

Folgen der AGPL: Der Quelltext muss für jeden, der die App erhält, verfügbar sein – das ist durch das öffentliche
GitHub-Repo erfüllt. Die Zusatzpflicht der AGPL (Quelltext auch für Nutzer über ein Netzwerk) greift hier
praktisch nicht, da die App keinen Server betreibt; sie schützt aber davor, dass jemand den Code als Clouddienst
weiterverwendet, ohne seine Änderungen offenzulegen. Die App bekommt einen Bildschirm „Open-Source-Lizenzen“,
der Lizenztext liegt in `LICENSE`.

---

## 4. Aufnahme-Kern (das Herzstück)

### 4.1 Vordergrunddienst (Foreground Service)
**Ja – die Aufnahme läuft zwingend als Vordergrunddienst.** Nur so beendet Android sie nicht nach kurzer Zeit:
Normale Hintergrundprozesse werden vom System nach wenigen Minuten eingefroren und haben ab Android 9 ohnehin
keinen Zugriff aufs Mikrofon. Ein Vordergrunddienst ist für den Nutzer durch eine dauerhafte Benachrichtigung
sichtbar und wird deshalb vom System bevorzugt behandelt. Er läuft weiter, wenn die App geschlossen,
aus der Liste der letzten Apps gewischt oder der Bildschirm ausgeschaltet ist.
(„Im Hintergrund“ meint in diesem Konzept also immer: *Vordergrunddienst bei ausgeschaltetem Bildschirm / geschlossener App*.)

- `RecorderService` mit `foregroundServiceType="microphone"`, Berechtigungen `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`.
- Ein `AudioRecord` liest dauerhaft 16 kHz / mono / 16 bit (genau das Format, das die Modelle erwarten → kein Resampling).
- `START_STICKY` + Watchdog: Stirbt der Prozess, wird neu gestartet und die angefangene Stunde in einer neuen Teildatei fortgesetzt.
- Ein `PARTIAL_WAKE_LOCK` hält die CPU während der Aufnahme wach. Das ist für das Lesen des Mikrofons nötig und kostet nur wenig Strom.
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

### 4.2a Speicherbedarf (gemessen)
**Messung:** 24:42 min deutsche Sprache (LibriVox-Hörbuch, gemeinfrei), umgewandelt in 16 kHz mono und
mit libopus (VBR, Sprachmodus) kodiert. Das entspricht dem Format, das Android per `MediaCodec` erzeugt.

| Format | pro Stunde | 24 h am Stück | Eignung |
|---|---|---|---|
| Unkomprimiert (PCM 16 kHz, 16 bit) | 115,2 MB | 2 765 MB | nur als Puffer im RAM |
| AAC 32 kbit/s (zum Vergleich) | 15,0 MB | 359 MB | – |
| Opus 24 kbit/s | 10,2 MB | 244 MB | sehr gute Qualität |
| **Opus 16 kbit/s (Standard)** | **6,8 MB** | **163 MB** | gute Sprachqualität, unkritisch für die Transkription |
| Opus 12 kbit/s („Sparmodus“) | 5,1 MB | 123 MB | noch gut verständlich |
| Opus 8 kbit/s | 3,4 MB | 82 MB | hörbare Artefakte → schlechtere Transkription, nicht empfohlen |

Opus ist also rund **17-mal kleiner als unkomprimiertes Audio** (16 kbit/s) und halb so groß wie AAC bei vergleichbarer Sprachqualität.

**Mit „Stille verwerfen“ (Standard: an).** Die VAD erkennt Sprache. Gespeichert werden nur Sprachabschnitte
plus je ~1 s Vor-/Nachlauf, damit kein Wortanfang fehlt. Hochrechnung für Opus 16 kbit/s
(die tatsächliche Sprachdauer pro Tag ist individuell, die App zeigt sie in der Statistik an):

| Gesprochen pro Tag (inkl. Umgebungsgespräche) | pro Tag | 30 Tage | 1 Jahr |
|---|---|---|---|
| 2 h (ruhiger Tag) | ~16 MB | ~0,5 GB | ~6 GB |
| 4 h (typisch) | ~33 MB | ~1,0 GB | ~12 GB |
| 8 h (Büro, viele Gespräche) | ~65 MB | ~2,0 GB | ~24 GB |
| zum Vergleich: alles speichern, 24 h | 163 MB | 4,9 GB | 60 GB |

**Weitere Daten:**
- Transkripte inkl. Volltextindex: grob 0,5 MB pro Tag bei 4 h Sprache → **< 200 MB pro Jahr**.
- Embeddings für die semantische Suche (Phase 4, int8 quantisiert): ~0,1 MB pro Tag → ~35 MB pro Jahr.
- Modelle (einmalig): Spracherkennung ~650 MB + optional Sprachmodell ~1,2 GB + Embeddings ~120 MB → **~2 GB**.

**Aufbewahrung** (einstellbar): Standard **Audio 30 Tage**, danach automatisch löschen. **Transkripte bleiben für immer.**
Einzelne Stellen lassen sich markieren („📌 behalten“), dann bleibt ihr Audio erhalten. Bei typischer Nutzung belegt die App
damit dauerhaft nur etwa **1 GB Audio + wenige hundert MB Text**. Wird der freie Speicher knapp (< 2 GB),
warnt die App und löscht notfalls das älteste nicht markierte Audio zuerst.

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
| Sprecher (Ph. 2) | WeSpeaker ResNet34 (Stimmabdruck je Abschnitt) | ~26 MB | 3D-Speaker CAM++, pyannote-Segmentierung |
| Sprachmodell (Ph. 4) | Qwen3 1.7B oder Gemma 3 1B, GGUF Q4 | 1–1,2 GB | Gemma 3 4B / Qwen3 4B auf starken Geräten (~2,5 GB) |

> Die genaue Geschwindigkeit (Echtzeitfaktor) messen wir in Phase 2 auf deinem Gerät. Erwartung für ein aktuelles
> Mittel-/Oberklasse-Gerät: deutlich schneller als Echtzeit; da typischerweise nur 2–5 h pro Tag tatsächlich
> gesprochen wird, ist die Last gut machbar.

---

### 5.3 Akku-Strategie: keine Live-Transkription
Vergleich: Cloud-Wearables wie Omi streamen live zum Handy und in die Cloud – genau das kostet laut deren eigenem
Support am meisten Akku, Nutzer wünschen sich dort ausdrücklich einen Batch-Modus. Andere Geräte (z. B. Plaud) setzen
auf bewusst gestartete Aufnahmen statt Dauerbetrieb. open2ndbrain kombiniert Dauerbetrieb mit Batch-Verarbeitung:

1. **Während der Aufnahme läuft nur, was billig ist:** Mikrofon lesen, energiebasierte Sprachaktivitätserkennung,
   Opus-Kodierung (Hardware/optimierter Systemcodec). Kein neuronales Netz im Dauerbetrieb.
2. **Transkription in Stapeln**, nie live: erst wenn eine Stundendatei abgeschlossen ist, dann am Stück mit voll
   ausgelasteten Kernen (kurz und effizient statt dauerhaft „ein bisschen“). Modelle werden nur für den Stapel geladen
   und danach freigegeben (~1 GB RAM).
3. **Modus „Nur beim Laden“**: alles über Nacht am Ladegerät – tagsüber praktisch kein Zusatzverbrauch.
   Standard „Automatisch“ verarbeitet sofort, aber nicht bei niedrigem Akku.
4. Gemessen am Entwicklungsrechner: Echtzeitfaktor 0,055 → 1 h Sprache ≈ 3–4 min Rechenzeit, auf dem Handy grob 10–15 min.
   Bei typisch 2–5 h Sprache pro Tag sind das 30–75 min Rechenzeit pro Tag, gebündelt.
5. **Ausnahme Meetings:** Ein markiertes Meeting wird nach dem Ende sofort (vorrangig) transkribiert – dafür wird die
   laufende Stundendatei beim Meeting-Ende vorzeitig abgeschlossen.

### 5.4 Markierungen: Meetings und Lesezeichen
Die 24/7-Aufnahme läuft immer weiter; Markierungen sind nur Zeitangaben darüber (`markers.json`):
- **Meeting starten/beenden** – im Aufnahme-Tab, in der Benachrichtigung und über eine eigene Schnelleinstellungs-Kachel.
  Meetings erscheinen als eigene Einträge (Titel, von–bis) mit allen Äußerungen aus dem Zeitraum, dateiübergreifend;
  ab Phase 4 mit automatischer Zusammenfassung und To-dos.
- **Merken** (Lesezeichen) – ein Tippen markiert die letzten 30 Sekunden als wichtig.
- Benachrichtigungs-Aktionen (Android erlaubt drei): **Meeting · Pause 15 min · Pause/Fortsetzen**; Stopp in der App.

## 6. Second-Brain-Index

### 6.1 Datenmodell (Room)
```
recordings  (id, start, end, file, mic, status)
segments    (id, recording_id, start_ms, end_ms, text, speaker_id?, pinned)
segments_fts  – FTS4-Volltextindex über segments.text (Umlaut-tolerant, Präfixsuche)
embeddings  (segment_chunk_id, vector BLOB)            – Phase 4
day_notes   (date, summary, todos, topics, people)      – Phase 4
speakers    (id, name, voice_embedding)                 – Phase 2 (speakers.json)
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
| **2 – Transkription & Sprecher** | ModelManager (Download/Import), sherpa-onnx-Einbindung, Parakeet v3, Worker-Pipeline, Lade-Einstellung, Benchmark-Anzeige, **Sprechererkennung** (dateiübergreifend, Umbenennen, „Das bin ich“, Zusammenführen) | Automatische Transkripte mit Sprechern |
| **3 – Index & Suche** | Room + FTS, Zeitleiste, Volltextsuche, Abspielen ab Zeitstempel, Aufbewahrungsregeln, Markdown-Export | Durchsuchbares Second Brain |
| **4 – Semantik & KI** | Embeddings, hybride Suche, llama.cpp, Tageszusammenfassung, To-dos | „OMI, aber offline“ |
| **5 – Komfort** | Fragen/Chat mit Quellen, Verschlüsselung, Widget, Obsidian-Sync-Ordner | Ausbau |

**Build & Verteilung**
Alles läuft über GitHub, **kein FTP-Upload**:
- **GitHub Actions** baut bei jedem Push eine APK (inkl. Unit-Tests und Lint). Die APK hängt als Artefakt am Workflow-Lauf.
- **GitHub Releases:** Ein Tag `v*` (z. B. `v0.1.0`) erzeugt automatisch ein Release mit der fertigen APK.
  Von dort installierst du sie direkt am Handy. Bequem für automatische Updates: die Open-Source-App
  **Obtainium** verfolgt die GitHub-Releases und meldet neue Versionen.
- **Signatur:** Damit Updates sich über die vorherige Version installieren lassen, muss jede APK mit demselben Schlüssel
  signiert sein. Dafür legst du bei Bedarf selbst die Secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`,
  `SIGNING_KEY_ALIAS` und `SIGNING_KEY_PASSWORD` an. Solange sie fehlen, baut der Workflow eine unsignierte Release-APK
  und eine Debug-APK. Kein Schlüssel landet im Repository.

---

## 10. Entscheidungen

| Thema | Entscheidung |
|---|---|
| Android-Versionen | alle modernen Versionen: Android 10–16 (minSdk 29, targetSdk 36) |
| Stille verwerfen | ja, Standard an; Opus 16 kbit/s, Sparmodus 12 kbit/s |
| Aufbewahrung | Audio 30 Tage (einstellbar), Transkripte unbegrenzt |
| Lizenz | GNU AGPL-3.0-or-later |
| Paketname | `de.open2ndbrain` |
| Auslieferung | nur GitHub (Actions + Releases), kein FTP-Deploy |
