# Open UK climbing data audit

Audit started 18 October 2026, after `INFRA_MAP.md` was written. This document distinguishes licensed data from mere public visibility. Measured coverage will be recorded by the ingestion report; no guidebook-equivalent completeness is claimed.

| Source | Licence and scope | Decision |
| --- | --- | --- |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | ODbL 1.0; worldwide editable geographic database, including UK crags, some individual routes, parking and paths | Primary database. Retain object ID/version/time, raw tags and geometry. Distribute derived database with ODbL notice and reproducible extraction. |
| [OS OpenData](https://www.ordnancesurvey.co.uk/products/open-data) | Open Government Licence; Great Britain, not Northern Ireland | Investigated as contextual mapping, not a route/topo catalogue. OS Open Zoomstack is a large general vector basemap, OS Terrain 50 is terrain, and OS Open Roads does not establish a walking approach. OS Open Names July 2026 city/town/village labels are integrated; initial compact packs use OSM geometry for boundaries and local paths instead of a national OS basemap. |
| [Open-Meteo](https://open-meteo.com/en/terms) | Forecast data CC BY 4.0; free API limited to non-commercial use and rate limits | Suitable for this free, ad-free app. Cache forecasts and disclose their age. Solar/aspect and drying calculations are estimates, never access or rock-safety guarantees. |
| [OpenClimbing export](https://openclimbing.org/export) | OSM-derived database under ODbL; images depend on their individual source licence | Alternative OSM delivery route, not an independent UK guidebook. Direct OSM extraction avoids unrelated worldwide records and other users' ticks. |
| [OpenBeta](https://github.com/OpenBeta/docs.openbeta.io/blob/develop/docs/faq.mdx) | Climbing data CC0, excluding photos and software | Investigate UK export coverage. Do not reuse a photo based on the database licence. Historical source provenance needs review before importing descriptions; this build never scrapes Mountain Project or other proprietary sites. |
| [Wikimedia Commons](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia) | Per-file licences and attribution, not one blanket media licence | Only fetch a photo after checking the Commons File page licence/author metadata. OSM-linked image paths may supply legitimate topo overlays. Unknown media licensing means link/metadata only. |
| UKC, Rockfax, BMC, commercial guidebooks | No bulk redistribution permission established | No ingestion or scraping. Preserve explicitly mapped UKC deep links only. Access updates on external sites are not copied. |

## Extraction and factual limits

Query OSM administrative areas for England, Scotland, Wales and Northern Ireland, not a rectangular box that includes Ireland, the Isle of Man or the Channel Islands. Exclude explicitly indoor gyms, building facilities, playground walls, climbing halls, high-ropes courses, bolts and route tops as location entries. Retain plain sport=climbing markers with outdoorStatus=unspecified, so incompletely tagged real locations such as Kilnsey are not silently lost. Natural/outdoor evidence is preserved separately. Individual routes are associated with crags only through OSM relation membership; unassociated routes remain accurately labelled standalone mapped climbs. Missing names use an explicit unnamed label and OSM ID, never an invented proper name.

Regional delivery zones may split England for manageable downloads; their labels describe delivery geography, not a claim about traditional climbing region boundaries. Raw source grades are preserved without converting between incomparable systems. Absence of a style, aspect, family-suitability tag or access restriction is unknown, not false or permission. Geometry on a map is not a face topo.

Parking within a bounded radius is marked **nearby, unverified** unless relation membership supplies association evidence. Nearby parking is not automatically suitable or legal. Paths retain node IDs, access/foot/conditional tags and geometry. Any app path calculation is a mapped-path estimate and must reject forbidden/private/conditional paths; disconnected endpoints produce unknown walk-in information. No straight-line distance is presented as a walking distance.

## Source access

Use serialized, cached queries with a meaningful user agent, bounded timeouts and backoff. The main Overpass instance's robots file disallows `/api/`; this pipeline instead evaluates the explicitly programmatic [Private.coffee endpoint](https://overpass.private.coffee/) and its access policy. If unavailable, [Geofabrik's UK extract](https://download.geofabrik.de/europe/united-kingdom.html) is an alternative that includes Northern Ireland and excludes the Crown Dependencies. No public raster tile bulk download is used.

`data/coverage.json` will hold measured counts and gaps. `data/raw/` will hold cached query responses plus request/provenance records. `data/packs/` will hold a JSON catalogue, regional SQLite databases, GeoJSON and actual MVT vector MBTiles, with hashes in the manifest. Rebuilding from the committed snapshot requires no network.

## Acquisition evidence

Private.coffee and its former Kumi host returned HTTP 500 for both POST and GET attempts on 18 October 2026. The pipeline switched to the advertised Geofabrik UK PBF (2,272,360,632 bytes, snapshot through 17 October 2026), obtained in one download and processed locally. No public OSM tile server is queried. Country polygons use relation membership, including boundary segments tagged only as rivers; a filter requiring every boundary way to carry a boundary tag would incorrectly omit Northern Ireland.

The official OpenBeta v2026-10-18 Parquet export contains 72,420 routes across 23 country labels and **zero UK rows**. `data/openbeta-audit.json` records the source hash and measured counts. No OpenBeta records are imported. Natural Earth public-domain 1:10 million UK land geometry is intersected with OSM country polygons for a low-detail backdrop, because administrative boundaries alone include territorial water. Exact OSM boundaries still govern UK record selection.

OS Open Names is obtained from the unauthenticated public OpenData download endpoint, with no OS account, API key, premium data or billing registration. Selected place labels and the original licence notice are retained in the reproducible snapshot.

## Measured release coverage

The final catalogue contains **828 mapped climbing locations and 2,093 individual routes**. These are source objects, not a claim of 828 guidebook-complete crags. There are 184 locations with individual route entries, 16 licensed photos, 67 photo route overlays, and 23 geographic route line geometries. Native grades are preserved: French, V/Hueco, UK adjectival and a few UK technical values occur; no Font grades are invented.

| Regional pack | Locations | Individual routes | Vector tiles |
| --- | ---: | ---: | ---: |
| Scotland | 48 | 5 | 2,713 |
| Wales | 286 | 1,274 | 2,804 |
| Northern Ireland | 19 | 0 | 412 |
| Northern England | 326 | 581 | 4,984 |
| Midlands and East England | 17 | 0 | 1,598 |
| Southern England | 132 | 233 | 2,668 |

There are 83,344 OSM contextual features and 16,581 OS place labels. 664 locations have nearby parking candidates. None has an explicitly mapped relation-member approach path in this snapshot: walk-in values therefore remain null until the app can form a defensible mapped-path estimate from chosen parking. Detailed path context is bounded around the positive outdoor climbing candidate points used in extraction, not a complete regional road network; recently retained unclassified markers may lack nearby context.

Only **3 locations have an explicit aspect**, **176 have rock type**, and **0 have a family-friendly tag**. Unknowns are prominent data gaps, not negative verdicts. There is no authoritative live nesting-ban feed. Scottish and Northern Irish route inventories are particularly sparse. The app's contribution queue is the intended route for improving these gaps without copying proprietary guidebooks.

Six SQLite databases, six genuine MVT MBTiles (15,179 tiles), GeoJSON fallbacks and the app catalogue are available under `data/packs/`. The largest compressed vector pack is 9.58 MB; raw JSON is larger and should be streamed rather than parsed entirely on the Android UI thread. `data/coverage.json` and the manifest hold machine-readable counts, bytes and hashes.

Commons licences were checked on permitted File pages because the Action API path is excluded by robots. Sixteen verified images use CC BY-SA 4.0, CC BY-SA 2.0 or CC0. Author, licence URL, source page, image hash, metadata-page hash and preview/change notice are in `data/photos.json`. Photo overlays retain the photo's applicable licence; OSM line data retains ODbL. No image was generated or copied from a guidebook.

Source relations can repeat route members. The normaliser removes repeated IDs within each location (Down Tor: 684 source member occurrences become 230 unique route IDs). The same route may still belong to multiple genuine source relations; national and regional route totals count unique OSM IDs. Generic sport=climbing markers retain an explicit unspecified outdoor status rather than claiming to be verified natural crags.
