# Architecture and acceptance plan

CragLight is a native Android application, Kotlin + Jetpack Compose, min SDK 26. Single activity, Material 3, Room for local records, MapLibre for interactive maps. Application ID: `org.craglight.app`.

## Data boundaries

- Reproducible Python ingestion turns licensed source snapshots into versioned regional JSON, SQLite and vector MBTiles. Manifest includes licence, counts, bytes and SHA-256 per file.
- Each crag and route retains source IDs, source URLs, version/timestamp where supplied and licence. Missing fields remain null, visibly labelled as not recorded.
- A strict publication gate filters the bundled index to ten curated complete guides; old mismatched pack caches are invalidated. A bundled national index supports initial browse. Regional packs supply offline map geometry and detailed data, and can be installed/removed independently. Installed pack state and checksums are persisted.
- Room stores personal ticks, contributions awaiting review and forecast cache. Optional community sessions are revocable and kept in memory; private drafts/photos use atomic app-private files. There are no analytics identifiers.
- MapLibre renders regional MVT from read-only MBTiles using a device-loopback-only tile transport. Only 127.0.0.1 may use cleartext; external requests require HTTPS. Large map JSON is streamed for local path calculations to avoid holding every region's geometry in memory. OSM node IDs prevent visually overlapping, disconnected paths from becoming junctions.
- A contribution is a separate proposed edit with licence attestation. It never mutates public climbing records before moderation.

## User journeys

1. Explore: map/list switch, search, selected region, grade system/range, style, rock, maximum walk-in, family-friendly and sun filters. Unknown values never pass a positive filter silently.
2. Crag: source-linked overview, access/restrictions, nearby parking and mapped paths, route list, source geometry, sun at chosen time, weather with fetched timestamp, tick a route, propose an edit.
3. Logbook: local-first ascent style/date/notes, edit/delete, spreadsheet-safe CSV export using Android's document picker.
4. Offline: view region size and coverage, verify and install pack, retain it across restart, delete a selected pack, import a downloaded file when offline.
5. About: source licences, coverage caveats, privacy, contribution review status.

## Walk-in and conditions

Choose location only on explicit user action; request Android location permission then, with manual parking selection always available. Proximity distance must be labelled as straight-line unless a connected mapped path exists. A path graph may estimate walking distance/time from mapped paths after excluding recorded access restrictions; untagged access remains unknown. Disclose missing connectivity and do not fabricate routes. Solar incidence uses latitude, longitude, UTC timestamp and crag aspect; unknown aspect means unknown sun exposure. Terrain shadows and overhangs are not modelled. Forecast freshness and drying estimates are explicit, never a guarantee of safe climbing or access.

## Verification

- Unit tests: UK trad/technical, French, Font/V grade parsing and ordering; invalid/unknown ranges; geographic distance; solar position with reference cases and timezones; filter inclusion/exclusion; approach connectivity; CSV formula escaping.
- Ingestion tests: UK-only boundary, indoor exclusion, provenance, deterministic output, source-derived attributes and missing values, vector tile validity.
- Android instrumented tests: browse/search/detail, filters, persistent tick + export, pack installation and persistence, queued contribution. Run with network disabled after pack install and verify map rendering and no crash.
- Release: separate freshly generated signing key; signed release APK and AAB; installation of release APK; launcher and app screenshots; licence/privacy/store drafts; final reference-state comparison.

## Community service

See [API contract](../community/API.md) and [service documentation](../community/README.md). The Android editor is offline-first and saves partial drafts atomically. Normalized image-space route coordinates share a transform with touch input, so zooming never changes saved positions. Google sign-in requires a dedicated OAuth project; server code verifies audience, expiry and a single-use nonce before issuing a revocable opaque session. Photos are decoded and re-encoded both on device and server. Private uploads, review and public publication are separate states. No service is declared live without a durable HTTPS deployment and real sign-in acceptance test.
