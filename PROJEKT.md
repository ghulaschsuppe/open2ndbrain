# open2ndbrain

Native Android-App: 24/7-Sprachaufnahme im Hintergrund, Transkription vollständig auf dem Gerät,
durchsuchbares „Second Brain“ mit optionalem lokalem KI-Modell. Komplett offline – keine Cloud.

- Vorbild: OMI AI – aber komplett offline
- Plattform: Android (Kotlin, Jetpack Compose), Android 10–16 (minSdk 29, targetSdk 36), Paket `de.open2ndbrain`
- Lizenz: GNU AGPL-3.0-or-later (siehe `LICENSE`)
- Sprache primär: Deutsch (Parakeet TDT 0.6B v3 multilingual)

## Status
Phase 1 – Recorder: umgesetzt, Gerätetest offen ([docs/GERAETETEST.md](docs/GERAETETEST.md)). Konzept: [docs/KONZEPT.md](docs/KONZEPT.md).

## Projektstruktur
- `app/src/main/java/de/open2ndbrain/core/` – Verträge (Schnittstellen) zwischen Engine und App, `AppContainer` (manuelle DI)
- `…/audio/` – Aufnahme-Engine: AudioRecord, VAD, Opus/OGG-Stundendateien, Sidecars, Bluetooth-Routing
- `…/service/` – Vordergrunddienst, Benachrichtigung, Neustart, Schnelleinstellungs-Kachel
- `…/ui/` – Compose-Oberfläche · `…/settings/` – DataStore-Einstellungen

## Lokal bauen
JDK 17+ und Android SDK (Platform 36) nötig: `./gradlew testDebugUnitTest lintDebug assembleDebug`

## Build & Auslieferung
- Ausschließlich über GitHub: GitHub Actions baut die APK, Tags `v*` erzeugen ein GitHub-Release mit APK.
- **Kein** FTP-Deploy auf dev.danerd.de für dieses Projekt.
- Signatur-Secrets (`SIGNING_*`) legt der Inhaber bei Bedarf selbst an – nie Schlüssel ins Repo.

## Konventionen
- Antworten/Doku auf Deutsch.
- Keine Zugangsdaten im Repository.
