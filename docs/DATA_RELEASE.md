# CragLight data v2 — curated guide

This revision contains **10 physical venues, 10 named route guides/link-ups, 10 licensed annotated photographs and 10 authored approaches with separate descent/return descriptions**. Four venues are in Wales, four in Northern Ireland and two in Southern England. Every entry states its partial crag scope. There is currently no Scottish seed guide. This is a small curated guide, not a nationwide or complete crag inventory. The old sparse OSM catalogue is not displayed.

Climbing prose and photographs were desk-reviewed from individually licensed Multi-pitch.com source pages on **19 October 2026**. The unchanged OpenStreetMap context source is Geofabrik's UK snapshot dated **17 October 2026**, retrieved 18 October. OS Open Names labels are **July 2026**; Natural Earth supplies low-detail land clipped to OSM boundaries. Per-record source URLs, rights evidence, dates and hashes are retained. No proprietary guidebook or UKC content was scraped. Desk review does not establish current access, safe equipment or field conditions.

## Files

Each of `scotland`, `wales`, `northern-ireland`, `england-north`, `england-midlands` and `england-south` has four files:

- `.json`: schema version 1, quality version 1 curated app pack, with approved route guides, source approaches/descent, explicit source parking points where available and contextual paths. Import requires the matching app catalogue revision.
- `.mbtiles`: actual gzip-compressed MVT, layer `craglight`, TMS rows, zooms 4–15. Together the packs contain 3,757 tiles and only ten public crag markers. These are sparse contextual maps, not full topographic navigation maps.
- `.sqlite`: queryable locations, routes, features and provenance for reuse and inspection.
- `.geojson`: regional map features for compatible offline GIS or map clients.

`manifest.json` records source snapshots and each regional file's SHA-256 and size. `all-crags.json` is the complete first-install catalogue; `basemap.geojson` is the low-detail national land backdrop. `SHA256SUMS` checks all release files except itself.

`photos.zip` for this revision contains ten unchanged source annotated JPEGs under `photos/`, `photo-licences.json`, and source/licence notices. Image paths are relative to Android assets. Source-tree paths in the notices refer to the repository. Images total 18,237,310 bytes. Lines, labels and belays are baked into the source pictures: these are not independently selectable or surveyed app overlays.

## Rights and limitations

Climbing text and annotated images by **Dan and Michel / Multi-pitch.com** use **CC BY-SA 4.0**. Nine original photographs use that licence; Dinorwig's original uses **CC BY 4.0**, with site-authored annotations/text under BY-SA 4.0. Keep creator/source/deed/change notices and share-alike terms. Images are unchanged; selected prose has normalized whitespace and native grade spelling aliases. OSM context remains **ODbL 1.0**; OS Open Names **OGL UK 3.0**, with OS/Royal Mail/National Statistics credits; Natural Earth public domain. See `DATA_LICENSE.txt`, `SOURCES.md` and `os-open-names-licence.txt`. No blanket licence is inferred for externally credited source-site images or guidebook covers.

Three guides contain source parking coordinates; others provide named-start text without a fabricated pin. Dinorwig's parking coordinate is kept separate from its OSM-supported crag location. Walk-in times are source estimates. There are no authored approach geometries or invented walk distances. Private/conditional path restrictions remain in OSM context. No family suitability or current access permission is inferred. Ambiguous Slieve Lamagan/Cneifion length and pitch summaries are omitted while full source descriptions remain available. Missing or conflicting guide candidates and community drafts remain outside publication.

The [source repository](https://github.com/delegentai/craglight) includes the compact geographic snapshot, reviewed source extracts, ten licensed JPEGs and reproducible pipeline. Rebuilding requires no source-data download. The deterministic audit matches 38 outputs byte-for-byte; 26 data/quality tests pass. No live forecast is bundled. Historical data-v1 assets remain historical and do not meet this catalogue's quality gate.
