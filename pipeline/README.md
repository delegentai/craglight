# Rebuilding CragLight data

Run from the independent CragLight project, using native Windows Python 3.12 or later. Dependencies are confined to the project virtual environment.

```powershell
python -m venv .pipeline-venv
.\.pipeline-venv\Scripts\python.exe -m pip install -r pipeline/requirements.txt
.\.pipeline-venv\Scripts\python.exe -c "import shutil; shutil.copytree('app/src/main/assets/photos', 'data/packs/photos', dirs_exist_ok=True)"
.\.pipeline-venv\Scripts\python.exe pipeline/build.py
.\.pipeline-venv\Scripts\python.exe -m unittest discover -s pipeline/tests -v
```

The normal rebuild uses `data/snapshot.json.gz`, `data/os-places.json.gz`, `data/uk-land.geojson` and `data/photos.json`; it requires no network. For a clean clone, copy the committed licensed photos from `app/src/main/assets/photos/` into ignored `data/packs/photos/` before building/testing, as shown above. CI performs this restoration, rebuilds packs and then synchronizes the Android assets without downloading national source files. Snapshot bytes, object provenance and deterministic gzip output are retained. SQLite and tile rows are written in a fixed order. `manifest.json` pins every output with SHA-256. `--skip-tiles` is a development option, not a complete release build. Use `pipeline/verify_reproducible.py` to rebuild into a separate temporary directory and compare all 27 catalogue/pack outputs byte-for-byte without changing the active packs. Evidence is written to `data/reproducibility.json`.

To deliberately refresh the source snapshots, download the public files into a new raw directory or move the existing raw downloads aside, then run:

```powershell
.\.pipeline-venv\Scripts\python.exe pipeline/download.py
.\.pipeline-venv\Scripts\python.exe pipeline/extract.py
```

Remove the old derived `data/os-places.json.gz` only when refreshing its OS source, then rebuild. Do not remove or modify anything under `C:\DelegentAI`. Download URLs and hashes live in `.source.json` sidecars and the derived manifest; the large original national datasets remain in ignored `data/raw/`.

The PBF is a national Geofabrik UK extract, including Northern Ireland. Exact OSM country relation membership forms the four selection polygons. Border ways are selected by relation ID membership, since some follow rivers and lack a `boundary` tag. The extraction retains outdoor climbing records and unspecified climbing markers, excluding explicit indoor facilities. A bounded 1.5 km context supplies mapped parking, cliffs and pedestrian-relevant highways around the initially identified outdoor points. Context is partial and is not a national routing graph; unclassified markers added later may have less surrounding geometry. Failed Overpass endpoints are documented in `DATA_AUDIT.md`; no request loop retries them indefinitely.

OS Open Names July 2026 supplies city/town/village labels, transformed from British National Grid to WGS84. It does not supply route grades, access permission, paths or Northern Ireland labels. The official OpenBeta export dated 18 October 2026 was inspected and contained no UK rows. It contributes no climbing records. No proprietary climbing content is fetched.

`pipeline/commons.py` checks each OSM-referenced image's permitted Commons File page, extracts the author and an explicitly supported open licence, then downloads its preview image. The Action API is not used because its path is disallowed by robots. The ledger and image hashes make this evidence reviewable. Unknown licences are rejected. The 16 retained images are CC BY-SA 4.0, CC BY-SA 2.0 or CC0, as recorded individually. This refresh step requires network access; rebuilding the packs does not.

## Pack contract, schema version 1

- `all-crags.json`: root `crags` array with complete catalogue details/routes/nearby parking, `attributions`, and low-detail country `map` FeatureCollection.
- `manifest.json`: `regions[]` has `id`, `name`, `bounds` (west, south, east, north), `cragCount`, `routeCount`, `featureCount`, `tileCount`, `files`, `bytes`, and `sha256`; file maps use keys `json`, `sqlite`, `geojson`, `mbtiles`.
- `<region>.json`: `region`, `crags`, `map` FeatureCollection, source date and attribution. This is the Android import format.
- `<region>.sqlite`: `crags`, `routes`, `features`, `provenance`, `metadata`, `crag_search` FTS4. App can import JSON into Room without depending on the source database's schema internals.
- `<region>.mbtiles`: MBTiles 1.3, TMS row numbering, gzip-compressed genuine MVT protobuf, source layer **craglight**, extent 4096, zooms 4–15. This is a sparse contextual map, not a terrain/topographic basemap.
- `<region>.geojson`: identical features to the JSON pack's map for an offline MapLibre GeoJSON source.
- `basemap.geojson`: country land shapes only; OS place labels are in the regional maps.

Each crag has stable `osm:<type>:<id>`, coordinates, country, delivery region, explicit nullable attributes, raw tags, independent native grade fields, grade ranges, routes and provenance. Route lists use explicit OSM relation membership; a route without a mapped parent is shown as a standalone mapped route. A route bottom that belongs to a mapped route way is not counted twice. Unnamed objects get a factual unnamed label with source ID.

`photos[]` contains verified `{ref,assetPath,url,sourceUrl,author,license,licenseUrl,width,height,changes,sha256,bytes}`. Paths are relative to Android assets, e.g. `photos/<hash>.jpg`; copy `data/packs/photos` to that location. Route `topoImageRef` matches `photo.ref` after changing underscores to spaces. `topoPath` points use normalized image coordinates: x increases rightward, y downward from the upper-left, each in [0,1], plus optional OSM point type and dotted-line marker. No image line is extrapolated beyond mapped points.

GeoJSON properties include `kind` (`land`, `crag`, `route`, `parking`, `path`, `cliff`, `place`). Paths have `tags`, `rawTags`, `nodeIds` and provenance. Shared OSM node IDs are routing evidence; intersecting lines without shared nodes are not automatically connected. Geometry coordinate order is longitude, latitude. Country boundary geometry is simplified only after the selection test.

`gradeRanges` is `{ukTrad:{min,max},ukTech:{min,max},french:{min,max},font:{min,max},v:{min,max}}`. Grades are never converted. Null means unknown. Original tags preserve unsupported systems and ambiguous strings.

Parking candidates include coordinates, access/fee tags, `straightLineDistanceM` and `association` (`nearby-unverified` or `osm-relation-member`). This distance is not a walk-in distance. Neither proximity nor relation membership certifies current access. Approach geometry is attached only for an explicit member tagged `path=climbing_access`; otherwise walk-in fields are null and the app may calculate a clearly labelled mapped-network estimate from a user-selected parking point.

## Moderation and provenance

The app's proposed edits must carry the target stable ID, original source version/timestamp, contributor source/licence declaration, field values and creation time. Store them separately with pending status. Never overwrite source records automatically. A moderator must verify provenance, licence, source geometry and access statements, record their decision, and publish an incremented data version. No remote moderation server or automatic OSM editing is implied by this pipeline.

Generated JSON and GeoJSON are UTF-8 with explicit LF line endings, so Git checkout normalization cannot change bytes after manifest hashing. Published redirect URLs omit query strings and fragments; snapshot hashes identify source bytes without redistributing temporary download signatures. Repeated source relation route members are deduplicated per location.
