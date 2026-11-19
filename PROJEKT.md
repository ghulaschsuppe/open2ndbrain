# open2ndbrain

Native Android-App: 24/7-Sprachaufnahme im Hintergrund, Transkription vollständig auf dem Gerät,
durchsuchbares „Second Brain“ mit optionalem lokalem KI-Modell. Komplett offline – keine Cloud.

- Vorbild: OMI AI – aber komplett offline
- Plattform: Android (Kotlin, Jetpack Compose), minSdk 29, targetSdk 35
- Sprache primär: Deutsch (Parakeet TDT 0.6B v3 multilingual)

## Status
Phase 0 – Konzept. Siehe [docs/KONZEPT.md](docs/KONZEPT.md).

## Build & Auslieferung
- GitHub Actions baut die APK.
- Entwicklerversionen per FTP nach `https://dev.danerd.de/open2ndbrain/` (Secrets `FTP_SERVER`, `FTP_USER`, `FTP_PASSWORD` – vom Inhaber manuell gepflegt, nie ins Repo).
- Dort liegt eine kleine PHP-8.1-Downloadseite (ohne Datenbank), die die verfügbaren APKs listet.

## Konventionen
- Antworten/Doku auf Deutsch.
- Keine Zugangsdaten im Repository.
