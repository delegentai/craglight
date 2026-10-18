# CragLight 1.0.0

CragLight is a free, native UK climbing guide for Android 8.0 and later. It has no ads, subscriptions, app accounts or analytics.

- Browse climbing locations and route lists on an interactive map, with search and filters for native grades, climbing style, rock, region, walk-in and known family suitability.
- Save any of six regional packs for offline maps and climbing data. Packs are checked against SHA-256 digests, and bundled copies support saving without signal. See the [data-v1 release](https://github.com/delegentai/craglight/releases/tag/data-v1).
- View licensed photographs with source-authored route overlays, available access notes and parking candidates. Compare sun exposure at a chosen time, cached Open-Meteo forecasts and clearly labelled drying estimates.
- Keep a private local logbook, edit ticks and export spreadsheet-safe CSV. Queue original observations locally, then explicitly send a browser review form or export them for moderation.

The dated open-data catalogue includes **828 climbing locations, 2,093 routes, 16 licensed photographs and 67 photo-route overlays**. Regional maps contain **15,179 vector tiles**, with OS Open Names place labels and a coarse Natural Earth/OSM land outline. Every climbing record retains source provenance; photograph and software credits are available offline.

Coverage is incomplete. Only 184 locations have individual route records, three have a mapped aspect, and none has a family-suitability tag in this snapshot. Nearby parking is a candidate, not an approved approach; no explicit verified approach relations were found. Source descriptions, restrictions and nesting information may be missing or outdated. Sun and drying suggestions are estimates, and the contextual map is not a complete navigation or terrain map. No UKC, Rockfax, BMC or guidebook content is redistributed. See the [data audit](../DATA_AUDIT.md) and [source credits](../SOURCES.md).

The APK is for direct Android installation. The AAB is a Play Console upload artifact and cannot be installed directly. This release does **not** mean the app has been published on Google Play. Before Play submission, the publisher must complete the store declarations and supply a dedicated private support/privacy contact; the included [privacy policy](PRIVACY.md) remains a draft until that contact is added.

See [QA evidence](QA.md) for observed test and emulator results, and [store submission notes](STORE_LISTING.md) for the remaining publishing steps. Export your logbook before uninstalling: local data is not uploaded or backed up to a CragLight account.
