# CragLight data v1

This release contains **828 mapped UK climbing locations, 2,093 distinct OSM route records, 16 licensed photographs and 67 source-mapped photo overlays**. It covers England, Scotland, Wales and Northern Ireland with six regional delivery packs. Coverage is incomplete and is not equivalent to a commercial guidebook.

The OpenStreetMap source is Geofabrik's UK snapshot dated **17 October 2026**, retrieved on 18 October 2026. OS Open Names place labels are from **July 2026**. Natural Earth supplies a low-detail land outline clipped to OSM country boundaries. Record IDs, versions, dates and native grade tags are retained. No proprietary guidebook or UKC content was scraped.

## Files

Each of `scotland`, `wales`, `northern-ireland`, `england-north`, `england-midlands` and `england-south` has four files:

- `.json`: schema version 1 app pack, with locations, routes, nearby parking and mapped path context. Import requires the matching app catalogue revision.
- `.mbtiles`: actual gzip-compressed MVT vector tiles in MBTiles, source layer `craglight`, TMS rows, zooms 4–15. Together the packs contain 15,179 tiles. These are sparse contextual maps, not full topographic navigation maps.
- `.sqlite`: queryable locations, routes, features and provenance for reuse and inspection.
- `.geojson`: regional map features for compatible offline GIS or map clients.

`manifest.json` records source snapshots and each regional file's SHA-256 and size. `all-crags.json` is the complete first-install catalogue; `basemap.geojson` is the low-detail national land backdrop. `SHA256SUMS` checks all release files except itself.

`photos.zip` contains the 16 original bundled preview files under `photos/`, their `photos.json` attribution and checksum ledger, and source/licence notices. Image paths in the catalogue are relative to the app assets directory. The source-tree paths mentioned in the unchanged licensing notice refer to the repository; the photograph ledger for this release is inside this separate archive.

## Rights and limitations

OSM data is **ODbL 1.0**; OS Open Names is **OGL UK 3.0**, including the supplied OS, Royal Mail and National Statistics acknowledgements; Natural Earth is public domain. Photographs separately use **CC BY-SA 4.0, CC BY-SA 2.0 or CC0**, as recorded per image. Keep creator, source, licence and change notices with reused images. See `DATA_LICENSE.txt`, `SOURCES.md` and `os-open-names-licence.txt`.

Nearby parking is explicitly unverified. A mapped path is not proof of current access; private and conditional access must be respected. Missing grades, aspect, family suitability, walk-ins and restrictions remain unknown. Only three locations have a recorded aspect, and none has a family-suitability tag in this snapshot. Generic climbing markers retain an unspecified outdoor status. Route totals count distinct source IDs, including routes assigned to more than one genuine source relation.

The [source repository](https://github.com/delegentai/craglight) includes compact licensed snapshots and the reproducible pipeline. Rebuilding these packs from those snapshots requires no source-data download. This release includes no live weather forecast.
