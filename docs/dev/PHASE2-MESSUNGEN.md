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
