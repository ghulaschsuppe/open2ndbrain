# Gerätetest Phase 1 – Checkliste

Alles hier ist per Unit-Tests nur als Logik geprüft. Diese Punkte brauchen ein echtes Gerät
(idealerweise Android 10, 13, 14 und 15/16). Logs: `adb logcat -s O2B-Audio RecorderService RecorderTile BootReceiver RecordingPlayer`.

## Installation
- [ ] APK aus GitHub Actions (Artefakt) bzw. GitHub-Release installieren; Debug-Variante heißt `de.open2ndbrain.debug` und kann parallel installiert sein.
- [ ] Onboarding: Rechtshinweis, Mikrofon, Benachrichtigungen (13+), Bluetooth (12+), Akku-Optimierung.

## Aufnahme & Dateien
- [ ] Start/Stopp aus der App, Benachrichtigung mit allen Aktionen (Pause, Pause 15 min, Stopp).
- [ ] OGG/Opus-Dateien sind abspielbar (in der App); Größe plausibel (~6,8 MB/h bei 16 kbit/s durchgehend).
- [ ] Stundenwechsel ohne Lücke; nach Sprache bis kurz vor der vollen Stunde wird die Datei kurz nach der vollen Stunde abgeschlossen.
- [ ] „Stille verwerfen“: Werden Wortanfänge abgeschnitten? Wie viel wird in ruhiger Umgebung / Büro / Straße / bei Musik gespeichert?
- [ ] Langzeitlauf ≥ 24 h: Akkuverbrauch, CPU, keine Abstürze.

## Hintergrund & Neustart
- [ ] Bildschirm aus, App aus „Letzte Apps“ gewischt → Aufnahme läuft weiter.
- [ ] Pause 15 min bei ausgeschaltetem Bildschirm → setzt pünktlich und ohne App-Öffnen fort.
- [ ] Prozess hart beenden (`adb shell am kill de.open2ndbrain`) → `.part`-Datei wird wiederhergestellt und ist abspielbar.
- [ ] Neustart durch das System (Android 11–14): Wird „Mikrofon im Hintergrund gesperrt“ erkannt, hilft „Aufnahme fortsetzen – antippen“?
- [ ] Geräteneustart und App-Update: Hinweis erscheint, Antippen startet die Aufnahme (Android 10: Direktstart).
- [ ] Schnelleinstellungs-Kachel: Start entsperrt und gesperrt (14+), Pause auf dem Sperrbildschirm.

## Bluetooth & Mikrofon
- [ ] BT-Headset verbinden/trennen während der Aufnahme (Android 12+ und 10/11), Aufnahme läuft durch.
- [ ] Ohne `BLUETOOTH_CONNECT`, Einstellung „Immer intern“, bestimmtes Gerät.
- [ ] Telefonat / WhatsApp-Anruf während der Aufnahme → „Mikrofon belegt“ → automatisch weiter.
- [ ] Mikrofon-Berechtigung im laufenden Betrieb entziehen.

## Sonstiges
- [ ] Speicher fast voll: Warnung (< 2 GB), Notlöschung (< 1 GB), Meldung „Speicher voll“.
- [ ] Hell/Dunkel, dynamische Farben, Adaptive-/Monochrom-Icon.
