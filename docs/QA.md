# Quality evidence

## Verified automated checks

The following results were observed on 18 October 2026. They do not by themselves establish emulator or release behaviour.

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM unit tests and debug Kotlin compilation | 41 tests passed; no failures, errors or skips | 14 data parser, 8 geography, 6 filtering, 5 grade, 5 sun, 3 CSV tests |
| Moderation queue tests | 14 tests passed | Input validation, transaction rollback, duplicate/conflicting identifiers, explicit rights review, immutable decisions, approved-only export and output-path boundaries |

Commands: `tools/gradle.ps1 testDebugUnitTest compileDebugKotlin --console=plain` and `python -m unittest moderation.test_queue -v`. Local machine-readable evidence is retained in `evidence/android-unit-tests.json` and `evidence/moderation-tests.json`. The Android result was captured at 20:11 UTC. Later changes require the release build and runtime checks recorded below.

## Independent implementation review

Review covered `DataJson`, `AppRepository`, `Weather`, `AppViewModel`, domain algorithms, photo overlays and the moderation importer. The following defects were corrected and protected by regression tests:

- Relation approaches now preserve source access and conditional restrictions. Private, prohibited and conditional paths are excluded from suggested approaches.
- Exact source grades on standalone boulders become a grade band without inventing routes. Grade and style filters must refer to the same route. Unknown values do not silently pass an active known-only filter.
- Parking access conditions, nesting-restriction descriptions and opening hours survive parsing. The UI can display their original wording and disable restricted parking choices.
- Original OSM node identities survive parsing and graph construction; coincident bridge coordinates with different node IDs do not create a false junction. Endpoint snapping is bounded and explicitly estimated.
- A malformed photo-overlay vertex rejects the whole line, preventing a fabricated segment across a gap. Missing source geometry stays missing. Photos require local asset paths, dimensions and complete attribution. Commons references tolerate source underscore/space variants.
- CSV output escapes quotes/newlines and neutralises spreadsheet formula prefixes.

Additional reviewed safeguards: regional data and MBTiles are checked against the bundled SHA-256 manifest; HTTP response sizes and timeouts are bounded; pack and local database files use app-private storage; external forecast requests use HTTPS with a 30-minute per-crag cache; source weather timestamps remain separate from derived drying suggestions. The moderation CLI makes no network requests, cannot auto-approve an import, and exports reviewed proposals separately from public packs.

Weather and approach calculations remain estimates. Unknown aspect is never reported as shade or sun; geometric solar position does not model nearby terrain or trees. Route tests establish graph behaviour, not legal access or on-ground path safety. Photo-line tests establish source preservation and parser rejection, not surveying accuracy.

## Offline legal notices

Bundled software notices are copied from official versioned upstream sources and resolved public artifacts. MapLibre's complete Android notice already incorporates its native dependency notice collection. Apache 2.0, MapLibre/gesture BSD notices, JetBrains notices and the Public Suffix List's MPL 2.0 are readable offline. Actual AAR/JAR notice files are preserved with artifact associations. The software inventory includes optional debug tools as a clearly labelled superset.

The original OS Open Names notice contains all three supplied credits. All 16 bundled photographs retain author, original file URL, licence URL and modification notice in the offline photo ledger. OSM, OS, Natural Earth and Open-Meteo credits are also bundled. Data licences retain their legal URLs; a failed direct download of optional ODbL/Creative Commons full-text copies did not change the source licence or its attribution.

## Runtime and release evidence

At the time this review section was written, the new licence viewer had not yet been compiled or exercised on an emulator. Emulator installation, offline map/pack behaviour, route interaction, logbook persistence, exports, contribution review form, licence-viewer scrolling, accessibility, signed APK/AAB verification and final reference-directory integrity are separate release checks. Record their actual outcomes here or link the final release report; do not infer them from JVM tests.
