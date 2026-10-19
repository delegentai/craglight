# CragLight

A free native Android climbing guide for the UK. Kotlin, Jetpack Compose, Room and MapLibre; Android 8.0+, package `org.craglight.app`. No advertising, subscriptions or analytics.

Version 1.1 starts with **10 crags, 10 selected routes/link-ups and 10 real annotated photo topos**. Every listed guide has a route description, authored approach, parking/start information and descent. Most cover one selected climb, not a whole crag. The former 828-location sparse catalogue is no longer published. See [DATA_AUDIT.md](DATA_AUDIT.md) and [SOURCES.md](SOURCES.md).

## Install and explore

Use the signed APK in [Releases](https://github.com/delegentai/craglight/releases), or the local `release/CragLight-1.1.0.apk`. The AAB is for Play Console, not direct installation.

The app opens on an interactive map. Switch to List for photo cards, open a guide, pinch to zoom its annotated topo, and use Approach for written directions and descent. Each guide states its route coverage and links the source and author. Source inspection is not a recent field check or assurance of access.

All ten guides and images are bundled. Save a regional pack in Offline for mapped paths and parking; matching bundled copies work without signal. Downloads/imports are verified against the bundled SHA-256 manifest. Version 1 packs are invalidated when upgrading so retired sparse pins cannot return. The map is contextual, not a complete terrain/navigation map.

Ticks remain private on the device and export to CSV. Search, filters and solar estimates work offline. Open-Meteo weather needs a connection to refresh and displays its age. Unknown aspect/family/walk-in details do not count as confirmed filter matches.

## Community contribution flow

Community supports original crag/climbing photo imports, normalized finger-drawn route lines, multiple route drafts, grades/descriptions, approaches, parking, access notes and descents. Partial contributions can be saved locally and exported without an account. Imported images are oriented, bounded and re-encoded without EXIF/GPS metadata.

The separate [community service](community/README.md) implements Google sign-in, private uploads, pending submissions, moderator-approved guides and galleries, contributor thanks, reports, account export/deletion and reuse of approved open photos with retained attribution. A photo alone does not make a crag public. Original contributions use CC BY-SA 4.0. Logbook entries never become public contributions automatically.

**Online community publishing is not live in the default build.** A dedicated Google OAuth client, durable HTTPS service and final private contact/operator details must be configured. Blank configuration clearly disables sign-in/upload; it never reports a simulated successful submission. The offline editor and exports work now. Existing legacy local text proposals remain accessible in About.

## Data and rights

MultiPitch's openly licensed guides provide the seed topos and authored prose. Authors, original image licences, source URLs, changes and hashes are retained. OSM paths/parking and OS Open Names labels provide map context; Natural Earth provides land outlines. Six regional packs are reproducibly generated as JSON, SQLite, GeoJSON and actual MVT MBTiles.

UKC and Rockfax libraries are not imported. Normal browsing/linking does not grant a licence to republish their databases, descriptions, photos or topos. See [reuse assessment](docs/CONTENT_RIGHTS.md).

## Build and verify

Use JDK 17 and Android SDK 36. The offline build needs no API credentials.

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
gradlew.bat connectedDebugAndroidTest
python -m pip install -r pipeline/requirements.txt
python pipeline/build.py
python -m unittest discover -s pipeline/tests -v
python -m pip install -r community/requirements.txt
python -m pytest community/test_service.py
```

The machine-specific scripts in `tools/` isolate the build/emulator from other projects. Run `scripts/sync-assets.ps1` after rebuilding packs. Release signing uses untracked `private/signing.properties`; never commit signing keys or credentials. Configure a real service only through `-PcommunityBaseUrl=https://... -PgoogleWebClientId=...` after its setup and live acceptance tests.

The software is Apache-2.0; data retains separate licences. [Privacy](docs/PRIVACY.md) · [Decisions](DECISIONS.md) · [Store submission](docs/STORE_LISTING.md). CragLight shares no signing identity, database, cloud project or user data with another app.
