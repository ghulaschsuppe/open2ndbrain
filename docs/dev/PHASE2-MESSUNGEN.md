# Phase 2 – Messungen auf dem Entwicklungsrechner

Getestet mit sherpa-onnx 2.0.2 (Python), 4 CPU-Threads (x86_64-Cloud-VM), deutsche LibriVox-Hörbücher (gemeinfrei),
vorher mit Opus 16 kbit/s (wie in der App) kodiert und wieder dekodiert. Skripte: `tools/model-eval/`.

## Spracherkennung: Parakeet TDT 0.6B v3 int8
- Laden: ~3 s. Echtzeitfaktor **0,055** (3 min Audio in 10 s). Auf dem Handy grob 2–5× langsamer zu erwarten → immer noch weit schneller als Echtzeit.
- Opus 16 kbit/s stört die Erkennung nicht erkennbar.
- **Wichtig:** Einzelne kurze VAD-Abschnitte (1–4 s) führen oft zu falscher Spracherkennung (englische Ausgabe
  „Mm, it's Viertel of zehn“). **Lösung:** VAD-Abschnitte mit Lücke ≤ 2 s zu Blöcken ≤ 20 s zusammenfassen (Originalaudio
  inkl. kurzer Pausen + 200 ms Rand). Ergebnis: korrekt „Erst Viertel auf zehn.“; außerdem minimal schneller.
- Silero VAD: `min_silence_duration=0.5`, `max_speech_duration=25`. Token-Zeitstempel (`result.timestamps`, Sekunden
  relativ zum Blockanfang) sind vorhanden → Aufteilung in Sätze und Zuordnung zu Sprechern möglich.

## Sprechererkennung
Künstliches Gespräch: 3 verschiedene Vorleser, 16 Wechsel, 177 s, Opus 16 kbit/s.

| Verfahren | Ergebnis |
|---|---|
| pyannote-Segmentierung 3.0 + Clustering (OfflineSpeakerDiarization), WeSpeaker | 3 Sprecher, ~70 % der Frames richtig, RTF 0,10 |
| dito mit 3D-Speaker CAM++ | 4 Sprecher, ~76 % |
| **Stimmabdruck je VAD-Abschnitt (≤ 8 s, Stille 0,25 s) + inkrementelles Cosinus-Clustering, WeSpeaker ResNet34, Schwelle 0,6** | **3 Sprecher, 58/59 Abschnitte richtig (98 %)**, Embeddings 1,2 s für 177 s |
| dito mit 3D-Speaker CAM++ | zerfällt in 5–11 Cluster (Schwelle schlecht übertragbar) |

**Entscheidung:** Abschnittsweises Embedding mit WeSpeaker ResNet34 (26 MB) und Abgleich gegen dateiübergreifend
gespeicherte Sprecherprofile (Schwelle Startwert 0,6, einstellbar). Einschränkung: Der Test hat saubere Sprecherwechsel
mit Pausen; bei schnellem Hin-und-her/Überlappung wird ein Abschnitt dem überwiegenden Sprecher zugeordnet.

## Wiedererkennung über Dateien (Kotlin-Pipeline, Linux-JNI)
Zwei Aufnahmen derselben 3 Sprecher mit verschiedenen Textstellen: `conv16.wav` (176 s) und `conv2_16.wav` (137 s).
Embeddings je Abschnitt ≥ 1 s exportiert mit `SpeakerEmbeddingDumpTest`, ausgewertet mit
`tools/model-eval/speaker_seeded_eval.py`.

**Gemessene Cosinus-Werte (WeSpeaker ResNet34)**

| Vergleich | Datei 1 | Datei 2 |
|---|---|---|
| Abschnitt ↔ Zentrum des eigenen Sprechers (ohne sich selbst), min / 5 %-Quantil | 0,79 / 0,80 | **0,65** / 0,77 |
| Abschnitt ↔ Zentrum eines fremden Sprechers, max / 95 %-Quantil | 0,74 / 0,70 | **0,87** / 0,77 |
| Zentrum ↔ Zentrum, gleicher Sprecher (Hälften bzw. über Dateien) | 0,94–0,98 | 0,97–0,98 |
| Zentrum ↔ Zentrum, verschiedene Sprecher | ≤ 0,75 | ≤ 0,81 |

In Datei 2 überlappen die Verteilungen schon innerhalb der Datei: Mit rein lokalem Clustering (Schwelle 0,6) werden
dort zwei Sprecher verschmolzen (67–69 %), unabhängig von einer höheren lokalen Schwelle (0,65–0,75: zusätzliche Profile,
62–69 %). Zentren über Dateien trennen dagegen sauber.

**Verfahren (seit Phase 2):** Bekannte Profile gehen als feste Start-Cluster in das Clustering jeder Datei.
1. Gierig je Abschnitt: bestes Zentrum unter Profilen (Schwelle **0,68**) und lokalen Clustern (Schwelle **0,6**),
   sonst neuer lokaler Cluster.
2. Zwei Runden Neuzuordnung jedes Abschnitts zum ähnlichsten Zentrum (Profile bleiben fest).
3. Lokale Cluster mit Zentren-Cosinus ≥ **0,88** zu einem Profil oder älteren lokalen Cluster zusammenlegen.
4. Neue Profile nur ab 3 s Sprache.

**Schwellenwahl** (Raster über Profil-Schwelle 0,55–0,75 × lokal 0,6–0,75 × Zusammenlegen 0,85–0,92; Szenarien:
Datei 1 → Datei 2 → Datei 1 sowie „ein Sprecher neu“ mit Profilen nur für 2 der 3 Sprecher):
- Profil-Schwelle: 0,6–0,75 besteht alle Szenarien (3 Profile, ≥ 95 %), 0,55 verschmilzt einen neuen Sprecher mit einem
  bekannten (69 %) → **0,68** (Mitte des Plateaus).
- Lokale Schwelle: 0,6–0,75 gleichwertig → **0,6** (bisheriger, validierter Wert).
- Zusammenlegen: 0,85–0,92 gleichwertig; liegt zwischen „verschieden ≤ 0,81“ und „gleich ≥ 0,94“ → **0,88**.

**Ergebnis Integrationstest** (`SherpaIntegrationTest`, Runde 2 mit Zuordnung aus Runde 1 bewertet):

| | vorher (lokal + Zentren-Abgleich 0,82) | nachher |
|---|---|---|
| Runde 1: Datei 1, neue Profile | 3 Profile, 99 % | 3 Profile, 99 % |
| Runde 2: Datei 2, Wiedererkennung | 3 Profile, **67 %** (zwei Sprecher in einem Profil) | 3 Profile, **98 %** |
| Runde 3: Datei 1 erneut | – | keine neuen Profile, 99 % |

Einschränkung: nur zwei Aufnahmen gleicher Qualität (Hörbücher). Für andere Mikrofone/Räume (BT-Headset, Telefon
auf dem Tisch) sind die Werte auf echten Alltagsaufnahmen nachzuprüfen; die Schwellen sind Konstanten in
`speakers/SpeakerClustering.kt`.

## Modellvergleich Deutsch (nach Nutzertest: Parakeet „ziemlich schlecht, Englisch dabei“)
FLEURS-de dev, 40 Sätze (427 s), Wortfehlerrate (WER), Echtzeitfaktor 4 Threads. „Alltag“ = −22 dB leise, Raumhall,
Rauschen 8 dB SNR, Opus 16 kbit/s; „+Norm“ = Lautstärke-Normalisierung vor der Erkennung. Skript: `tools/model-eval/asr_benchmark_de.py`.

| Modell | Größe | WER sauber | WER Alltag | WER Alltag+Norm | RTF | Zeitstempel |
|---|---|---|---|---|---|---|
| Parakeet TDT 0.6B v3 (auto) | 670 MB | 7,9 % | 66,3 % | 59,0 % | 0,12–0,19 | ja |
| **NeMo FastConformer Deutsch (CTC)** | 132 MB | 6,6 % | 19,2 % | **19,0 %** | **0,02–0,05** | ja |
| **Canary 180M flash, src=de** | 207 MB | **5,9 %** | 17,8 % | **16,6 %** | 0,09 (sauber 0,34) | nein |
| Whisper small, language=de | 375 MB | 9,3 % | 35,5 % | – | 0,25 | nein |

**Befund:** Parakeet v3 bricht bei leisen/verrauschten Aufnahmen völlig ein und erkennt die Sprache falsch (→ Englisch).
Feste Sprache bzw. einsprachige Modelle sind drastisch robuster. **Standard ab jetzt: FastConformer Deutsch**
(schnell, Zeitstempel); **Canary** als „genau“-Option (ohne Token-Zeitstempel → Äußerungen über VAD-Abschnitte).
Lautstärke-Normalisierung hilft Parakeet/Canary leicht, schadet nie → immer an.

## Alle ASR-Modelle in der Kotlin-Pipeline (Integrationstest, Linux-JNI, 4 Threads)
`SherpaIntegrationTest` mit `O2B_ASR_MODEL=<id>`; conv16.wav (176 s) → conv2_16.wav (137 s) → conv16.wav erneut.
Lautstärkenormalisierung vor der ASR an; Modelle ohne Token-Zeitstempel (Canary, Whisper) erkennen Einheiten aus
feinen VAD-Abschnitten desselben Sprechers (≤ 15 s), Wortzeiten werden proportional geschätzt.

| Modell | Sprecher R1 / R2 / R3 | neue Profile R2/R3 | RTF | Text (Eindruck) |
|---|---|---|---|---|
| FastConformer Deutsch (CTC) | 100 / 100 / 100 % | keine | 0,03 | sehr gut, Eigennamen gut („Pokrowskoje“) |
| Canary 180M (de) | 100 / 98 / 100 % | keine | 0,30–0,45 | sehr gut, saubere Satzgrenzen |
| Parakeet v3 | 99 / 96 / 99 % | keine | 0,15–0,24 | mehr Fehler („Übermorgen vielleicht schnelle tote Leiche“) |
| Whisper small (de) | 100 / 99 / 100 % | keine | 0,31–0,41 | ordentlich, lässt vereinzelt Abschnitte aus / Fragmente („der“) |

Canary ist in der Pipeline langsamer als im Satz-Benchmark (RTF 0,09), weil kurze Einheiten den festen Decoder-Aufwand
je Aufruf stärker gewichten. **Lautstärkenormalisierung vor dem Embedding schadet** (FastConformer-Lauf: 2 statt 3 Profile,
78 %) – sie bleibt nur vor der ASR aktiv (`normalizeForEmbeddings = false`).
