# Quality evidence

## Verified automated checks

The following results were observed on 18 October 2026. They do not by themselves establish emulator or release behaviour.

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM unit tests, debug Kotlin compilation and lint | 51 tests passed; no failures, errors or skips; lint passed | 15 data parser, 13 geography, 6 filtering, 9 grade, 5 sun, 3 CSV tests |
| Ingestion pipeline tests | 18 tests passed | Source filtering, geographic boundaries, pack integrity and provenance, deterministic output, vector data and licensed photo handling |
| Reproducible data build | 27 of 27 outputs byte-identical | Rebuilt from the retained input snapshots; no network-dependent differences |
| Moderation queue tests | 14 tests passed | Input validation, transaction rollback, duplicate/conflicting identifiers, explicit rights review, immutable decisions, approved-only export and output-path boundaries |

Commands include `tools/gradle.ps1 testDebugUnitTest compileDebugKotlin --console=plain`, `python -m unittest discover -s pipeline/tests -v` and `python -m unittest moderation.test_queue -v`. The final Android count was checked directly against its JUnit XML reports; local machine-readable evidence is retained in `evidence/android-unit-tests.json` and `evidence/moderation-tests.json`. The pipeline maintainer confirmed 18 passing tests and 27 identical rebuilt files. Runtime and release checks are recorded separately below.

The public clean Ubuntu [CI run 39164557383](https://github.com/delegentai/craglight/actions/runs/39164557383) also completed successfully for production and test source commit `14224d195638a02108d20616dc0cd4f76c70d803`. This CI check is separate from the Windows emulator and signed artifact verification below.

## Independent implementation review

Review covered `DataJson`, `AppRepository`, `Weather`, `AppViewModel`, domain algorithms, photo overlays and the moderation importer. The following defects were corrected and protected by regression tests:

- Relation approaches now preserve source access and conditional restrictions. Private, prohibited and conditional paths are excluded from suggested approaches.
- Exact source grades on standalone boulders become a grade band without inventing routes. Grade and style filters must refer to the same route. Unknown values do not silently pass an active known-only filter.
- Parking access conditions, nesting-restriction descriptions and opening hours survive parsing. The UI can display their original wording and disable restricted parking choices.
- Original OSM node identities survive parsing and graph construction; coincident bridge coordinates with different node IDs do not create a false junction. Endpoint snapping is bounded and explicitly estimated.
- A malformed photo-overlay vertex rejects the whole line, preventing a fabricated segment across a gap. Missing source geometry stays missing. Photos require local asset paths, dimensions and complete attribution. Commons references tolerate source underscore/space variants.
- CSV output escapes quotes/newlines and neutralises spreadsheet formula prefixes.
- Native V modifiers, explicit adjacent V/French ranges, French low grades and broad technical grade 3 are handled without cross-system conversion. Interval filters preserve both source endpoints. Duplicate relation memberships do not duplicate route IDs.

Additional reviewed safeguards: regional data and MBTiles are checked against the bundled SHA-256 manifest; HTTP response sizes and timeouts are bounded; pack and local database files use app-private storage; external forecast requests use HTTPS with a 30-minute per-crag cache; source weather timestamps remain separate from derived drying suggestions. The moderation CLI makes no network requests, cannot auto-approve an import, and exports reviewed proposals separately from public packs.

Runtime parking selection exposed repeated graph construction for every nearby crag. The revised routing API builds one mapped graph after excluding recorded restrictions and runs Dijkstra once per origin, then reconstructs destination estimates from their nearest segment endpoints. Paths with unknown access remain included; their presence does not establish permission. Destination projections never become graph junctions. CPU loops check cancellation, and a superseded request cannot overwrite a newer origin or leave the loading indicator stuck. Five added tests cover batch connectivity, bridge separation, direct same-segment paths, snap limits and cancellation; all passed within the 51-test JVM result above.

Weather and approach calculations remain estimates. Unknown aspect is never reported as shade or sun; geometric solar position does not model nearby terrain or trees. Route tests establish graph behaviour, not legal access or on-ground path safety. Photo-line tests establish source preservation and parser rejection, not surveying accuracy.

## Offline legal notices

Bundled software notices are copied from official versioned upstream sources and resolved public artifacts. MapLibre's complete Android notice already incorporates its native dependency notice collection. Apache 2.0, MapLibre/gesture BSD notices, JetBrains notices and the Public Suffix List's MPL 2.0 are readable offline. Actual AAR/JAR notice files are preserved with artifact associations. The software inventory includes optional debug tools as a clearly labelled superset.

The original OS Open Names notice contains all three supplied credits. All 16 bundled photographs retain author, original file URL, licence URL and modification notice in the offline photo ledger. OSM, OS, Natural Earth and Open-Meteo credits are also bundled. Data licences retain their legal URLs; a failed direct download of optional ODbL/Creative Commons full-text copies did not change the source licence or its attribution.

## Runtime and release evidence

The final offline instrumentation flow passed on the dedicated Android 16 emulator with Wi-Fi and mobile data disabled: one test, no failures/errors/skips, 40.013 seconds for the test body. It exercised visible cluster-count pixels and cluster expansion; real regional MBTiles paths, cliffs and parking; a licensed photo and selectable source overlay; a private logbook tick and CSV escaping; a locally queued proposal; bundled regional installation; activity recreation and persistence; and opening/scanning the offline Apache licence text. The test does not submit a public contribution. Local evidence: `evidence/ui-test/result.xml` and its screenshots.

The real Clean Wall parking example returned 836.55 m within the test's 3 m tolerance. The UI displayed 836 m, 12 minutes and 16 m of unverified endpoint connections. No isolated routing stopwatch was recorded, so the 40-second complete flow must not be reported as routing computation time.

Visual review found that this PC's old SwiftShader emulator translator omitted bitmap icons and dashed-line pixels despite reporting their geometry. The same APK rendered correctly after the dedicated emulator switched to host graphics (AMD Radeon 8060S). Final screenshots visibly contain cluster numerals, parking symbols and green path dashes. Tests now check actual numeral pixels, not merely feature-query results. The app's map still uses local sources and OpenGL; no network glyph or sprite service was added. See the local toolchain notes for the reproducible emulator configuration.

The separate online instrumentation test passed in 15.37 seconds with no failures/errors/skips. It required a genuine public `data-v1` JSON download and checked installed JSON and MBTiles SHA-256 digests against the manifest. It fetched a real Open-Meteo forecast covering the current time, then disabled both radios and verified the saved forecast's retrieval time stayed unchanged after activity recreation and a refresh within the cache window. **Use my location** returned an injected emulator GPS position of 53.066, −4.075 within 0.002 degrees while offline. This verifies permission/UI/location processing, not real-world GPS accuracy. Local evidence: `evidence/online-test/result.xml` and screenshots 10–12.

Runtime coverage is the dedicated Android 16 emulator. Other Android versions and physical devices have not been exercised in this release check.

The actual signed release APK was installed and opened with both radios disabled on the dedicated API 36 emulator. It was non-debuggable, displayed all 828 catalogue entries and native map/count pixels, and remained alive without a recorded crash. Its APK v2 signature verifies with certificate SHA-256 `424a616906bcc8a97c412d89189a522d98216a1bc1379b9f980cee5e5fca28c6`.

Official bundletool validation and universal APK generation both passed for the signed AAB. Its random-access JAR cryptographic signature also verifies. The generated validation APK was unsigned and was neither installed nor published. The AAB enables `PAGE_ALIGNMENT_16K`; both APKs have 16 KB-aligned uncompressed native ZIP offsets and at least 16 KB alignment for every packaged 64-bit ELF load segment. These are structural compatibility checks; this run did not test a device using 16 KB memory pages. The JDK streaming-signature warning was traced to the manifest's position as the last ZIP entry, with no duplicate entries or CRC errors. See [release bundle validation](RELEASE_BUNDLE_VALIDATION.md) for the warning diagnosis, reproduction commands and evidence inventory. No Play Console acceptance or publication is claimed.

| Signed release artifact | SHA-256 |
| --- | --- |
| `CragLight-1.0.0.apk` | `f0e4ea6f90ab0cc4cf75f4173d9817d4fc46a1610af29daa03c73e8cf68a3050` |
| `CragLight-1.0.0.aab` | `70a1cb5696fcbc80860d42cafc0242bd297ef75d8fa80b03fc8cab1ea16a289e` |

Local release evidence includes `evidence/release/emulator-result.json`, `release-explore.png`, `release-map.png`, `apk-alignment.json`, `bundletool-apk-alignment.json` and the signature/validation logs. All checks preserved the original release artifacts.

## Reference-directory integrity

The final read-only snapshot at 20:21 UTC on 18 October 2026 matches the initial snapshot structurally: exact Git HEADs and status output, 3,082 and 364 tracked-file records, Git metadata and the root instruction-file evidence. Secret-shaped files were compared by metadata only; their contents were never read. Evidence is in the local `evidence/infra-comparison.json`.

The reference repositories were already dirty at the start, with one and 345 status entries respectively. Those entries were preserved exactly. They were neither cleaned nor reset, and the result must not be described as a clean initial working tree. An initial serializer-based false alarm was resolved by comparing parsed JSON values rather than formatting/escaping differences; no underlying evidence differed.
