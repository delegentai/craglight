# Rebuilding the curated CragLight guide

Run from the independent CragLight project, using native Windows Python 3.12 or later. Dependencies are confined to the project virtual environment.

```powershell
python -m venv .pipeline-venv
.\.pipeline-venv\Scripts\python.exe -m pip install -r pipeline/requirements.txt
.\.pipeline-venv\Scripts\python.exe pipeline/build.py
.\.pipeline-venv\Scripts\python.exe -m unittest discover -s pipeline/tests -v
.\.pipeline-venv\Scripts\python.exe pipeline/verify_reproducible.py
```

The normal build uses reviewed `data/curated/source-records.json`, its ten source JPEGs, `data/snapshot.json.gz`, `data/os-places.json.gz` and `data/uk-land.geojson`. It requires no network, research directory or copy from Android assets. CI builds directly from these committed inputs. Generated photos are reconciled to the exact public set; obsolete generated images are removed. SQLite/tile ordering and gzip timestamps are deterministic, text uses UTF-8/LF and manifest hashes pin outputs. The reproducibility script compares all **38** catalogue/regional/ledger/image outputs in an isolated temporary directory and writes `data/reproducibility.json`. `--skip-tiles` omits stale MBTiles from the manifest and is not a complete release build.

The current public seed has ten venues, with one named route or explicit link-up each. `curated.py` fails closed without reviewed useful licensed imagery, native grade and substantive route text, authored approach, named parking/start, separate descent, evidenced UK location, scope, source date and access notes. Missing/inconsistent candidates remain held; see `DATA_AUDIT.md`. Deliberate reviewed-source refresh uses `import_curated_research.py --research-dir <reviewed-research-directory>`; this is an importer of previously inspected open records, not a crawler. Neither incomplete community submissions nor raw OSM locations automatically become public crags.

Container `schemaVersion` remains 1, adding `catalogueMode: curated` and `qualityVersion: 1`. Crag IDs are `curated:<stable-slug>`. Public fields include `publicationStatus: published`, `qualityVersion`, `coverageScope`, `approachDescription`, `descentDescription`, `parkingDescription`, `approachSourceUrl`, `sourceUrl`, `contentCheckedAt`, and `accessNotes`. `fieldProvenance` distinguishes geographic OSM data from CC climbing prose. Route descriptions and source URLs are retained, with optional `lengthM`/`pitches`; ambiguous totals are null. `photos[].kind: annotated-topo` means the JPEG already contains the lines/labels. `topoImageRef` matches that photo, `topoPath` is null, and no independent line is invented. `photo-licences.json` records the exact public media set.

Only the ten eligible IDs become crag map features. Three venues have explicit authored parking coordinates; others retain named-start text without invented pins. `walkInTimeMin` is a source estimate with field provenance; distances/approach geometry stay unknown. Six region containers still exist, including empty public-catalogue regions. They contain contextual maps, never replacement sparse crag lists.

## Historical geographic-source workflow

The remaining extraction/schema notes describe the original OSM research snapshot and legacy transformation retained for audit/tests. The default `build.py` no longer exposes those sparse objects. The old `data/photos.json` ledger and Commons images are historical inputs, not current public media.

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
