# CragLight

A free, native Android climbing guide for the United Kingdom. Kotlin, Jetpack Compose, Room and MapLibre. Android 8.0+; package `org.craglight.app`.

CragLight combines openly licensed climbing records with offline regional maps, a private logbook, grade/style/rock/region/walk-in filters, solar aspect calculations, cached Open-Meteo forecasts and a moderated contribution path. It has no ads, subscriptions, accounts or analytics.

**Coverage is incomplete.** This is an open-data guide, not a licensed guidebook catalogue. Missing grades, descriptions, restrictions and photographic topos stay visibly unknown. Nearby parking does not imply an approved approach. See [DATA_AUDIT.md](DATA_AUDIT.md) and [SOURCES.md](SOURCES.md).

## Install

Download the signed APK from [Releases](https://github.com/delegentai/craglight/releases). Android may ask you to allow installation from the browser/file manager. The AAB is for Play Console submission, not direct installation.

Open **Offline** and save a region. Downloads are checked against the bundled SHA-256 manifest; a bundled copy lets you save it without signal. Maps, catalogue, search, sun calculations, ticks and queued edits then work offline. Weather needs a connection to refresh and always retains its fetched time. You can also import a matching regional JSON downloaded from a data release.

## Data and maps

The release catalogue has **828 climbing locations, 2,093 routes, 16 licensed photos and 67 photo-route overlays**, derived from a dated Geofabrik UK OSM extract. It also includes OS Open Names populated-place labels and a Natural Earth/OSM contextual land outline. Six regional packs provide JSON, SQLite, GeoJSON and 15,179 genuine MVT tiles in MBTiles (source layer `craglight`). Android renders these vector tiles from read-only local files; it does not bulk-download public raster tiles.

The ingestion manifest includes source dates, attribution, file sizes, counts and SHA-256 digests. Each climbing record retains its OSM ID, version, timestamp and licence. The compact source snapshots and reproducible pipeline are included. Large original national downloads are excluded from Git. See [pipeline/README.md](pipeline/README.md).

## Logbook and contributions

Ticks are local, editable and exportable as spreadsheet-safe CSV. Back them up before uninstalling the app. A personal free-text ascent is not automatically published as route data.

Crag corrections are queued locally under an explicit original-work CC0 declaration. The app can open a browser review form or export the queue. It never claims a proposal was sent merely because it was saved. Moderation checks evidence and reuse rights before a later data release; [the review workflow](docs/MODERATION.md) includes an auditable command-line queue.

## Build and test

Use JDK 17, Android SDK API 36 and the checked-in Gradle wrapper. Set `ANDROID_HOME` or a local `sdk.dir`. No API keys are needed.

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
gradlew.bat connectedDebugAndroidTest
python -m pip install -r pipeline/requirements.txt
python -c "import shutil; shutil.copytree('app/src/main/assets/photos', 'data/packs/photos', dirs_exist_ok=True)"
python pipeline/build.py
python -m unittest discover -s pipeline/tests -v
python -m unittest discover -s moderation -p test_*.py -v
```

The initial build machine uses isolated scripts under `tools/`; its machine-specific paths are not required by the app. `scripts/sync-assets.ps1` copies and verifies generated regional JSON packs before building. Keep assets and manifest from the same data revision.

Release signing reads an untracked `private/signing.properties`. See [signing](docs/SIGNING.md). Do not publish the keystore, passwords, service credentials or local evidence manifests.

## Limitations that matter

- Open route and topo coverage is much smaller than commercial guides. Only 3 locations have a mapped aspect and none has a family-suitability tag in this snapshot; positive filters may return very few or no matches. No proprietary UKC, Rockfax, BMC or guidebook content is redistributed.
- Solar exposure assumes a vertical face and its recorded aspect. Shadows, trees, overhangs and cloud effects can change actual sunshine.
- Walking estimates require connected permitted paths and include explicitly labelled endpoint connections. The sparse contextual map is not a full navigation or terrain map.
- Weather-based drying cannot detect seepage or assess rock damage. Access and nesting restrictions may change after a data snapshot.
- The app has no hosted anonymous moderation API; users explicitly send a GitHub review form or export their pending proposal.

## Publishing and privacy

[Store listing and submission steps](docs/STORE_LISTING.md) · [Privacy draft](docs/PRIVACY.md) · [Architecture](docs/ARCHITECTURE.md) · [Decisions](DECISIONS.md).

The software is Apache-2.0. Data retains its separate source licence and attribution in [SOURCES.md](SOURCES.md). The project is independent and does not share another app's signing identity, database, cloud project or user data.
