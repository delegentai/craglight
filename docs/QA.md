# CragLight 1.1.0 quality evidence

Updated 19 October 2026. This report describes the curated 1.1.0 guide and community implementation. Earlier release checks and catalogue counts are not evidence of the current build.

## Verified checks

| Check | Observed result | Evidence and scope |
| --- | --- | --- |
| Android JVM tests | **63 passed**, zero failures, errors or skips | `app/build/test-results/testDebugUnitTest/*.xml`: 10 community draft, 15 parser, 13 geography, 6 filtering, 9 grade, 5 sun, 3 CSV and 2 guide-quality tests |
| Final Android build and lint | Debug unit tests, debug lint, release APK and release AAB tasks passed | Release operator confirmed the final combined Gradle run; lint reported zero errors and 22 warnings |
| Android offline instrumentation | **4 passed**, zero failures, errors or skips; suite time **19.913 seconds** | `evidence/ui-test/result.xml`; dedicated Android 16 / API 36 emulator |
| Community API tests | **31 passed**, zero failures, errors or skips; suite time **7.636 seconds** | `evidence/community-test-results.xml`; isolated local databases and signed authentication fixtures |
| Ingestion and guide-quality tests | **26 passed** | Validated by the pipeline maintainer; current dataset validation is recorded in [DATA_AUDIT.md](../DATA_AUDIT.md) |
| Reproducible data build | **38 of 38 outputs byte-identical** | `data/reproducibility.json`, verified at 12:27:45 UTC; network-free rebuild from retained curated records, licensed JPEGs and compact geographic snapshots |

The reproducibility check compares catalogue and regional JSON, SQLite, GeoJSON, genuine vector MBTiles, the media ledger and images by SHA-256. It demonstrates deterministic output from those inputs, not current on-ground access or climbing conditions.

The 22 lint warnings comprise ten dependency-version notices, one Android Gradle Plugin notice, one modifier-parameter warning, one obsolete-SDK-check warning, one Kapt warning, one monochrome-icon warning and seven Kotlin-extension suggestions. A successful lint run is not a claim that it emitted no warnings.

## Current guide coverage and provenance

The public catalogue contains **10 physical venues, 10 selected routes or explicitly labelled link-ups and 10 licensed annotated photo topos**. All ten records have source-authored approach and separate descent/return information. Coverage is four venues in Wales, four in Northern Ireland and two in Southern England. There is no current Scottish seed record meeting the same publication gate, and these are selected climbs rather than complete crag inventories.

Three venues have explicit source parking coordinates; the remaining seven retain named parking/start descriptions without an asserted parking pin. There are no source-authored approach polylines. Mapped OSM paths are contextual data and can support a labelled estimate only where the graph connects after recorded restrictions are excluded. Unknown access remains unknown; a mapped path does not establish permission.

The retained primary-source records include page URLs and hashes, original image rights evidence, image hashes, native grades, source/check dates and editorial decisions. Nine original photographs use CC BY-SA 4.0; Dinorwig's original uses CC BY 4.0, with its annotations/text under CC BY-SA 4.0. Individual licences and authors remain visible in the app and media ledger. Current details are in [DATA_AUDIT.md](../DATA_AUDIT.md), [SOURCES.md](../SOURCES.md), `data/coverage.json` and `data/curated/source-records.json`.

This was a desk review, not a field inspection. Source discrepancies and incomplete candidates are held outside the public catalogue. Access, tides, nesting restrictions, parking charges, fixed equipment and descent conditions require current independent assessment.

## Offline Android behavior exercised

The four passing instrumentation tests exercised:

- Loading exactly ten publishable guides; showing native map features and changing zoom through a pinch gesture.
- Searching for Bosigran, opening its actual licensed photo topo, interacting with the photo and verifying the displayed authored approach text.
- Saving a private logbook tick, installing a bundled regional JSON/vector pack while offline, recreating the activity and finding the saved tick.
- Drawing a route line with a finger gesture, checking bounded normalized coordinates, undoing it, drawing again and retaining the draft across activity recreation. The partial draft had neither ownership consent nor a submission timestamp.
- Rotated-photo import with correct pixels/dimensions, removal of GPS/artist metadata and preservation of the original source file.
- Mirrored-photo normalization before route coordinates are drawn.

Screenshots are retained under `evidence/ui-test/` with `v11-` names. The suite duration covers the combined instrumented checks; it is not a routing-performance measurement. The Android tests did not sign into Google, upload to a real service or publish a contribution.

## Community service verification

The 31 local backend tests cover Google's official verification library with a locally signed RSA token/certificate fixture, including signature, issuer, audience and expiry rejection. They also cover a single-use nonce, session revocation, omission of Google email/real name from stored/exported account data, pseudonyms, optional avatar moderation, image validation and metadata removal, ownership checks, partial submission, complete-guide gating and refusal of moderator self-approval.

Publication tests verify required route prose, parking/start guidance and descent; separate gallery approval; credit limits; reuse of an approved shared photo; additive route revisions; and original photo, route and guide-section attribution. They verify that oversized merged guides leave the previous publication intact, that reports can hide affected content, and that account deletion removes private records/uploads and anonymises inherited attribution in another contributor's surviving reviewed guide. This last case is deliberate: deletion does not guarantee removal of every previously incorporated route or text passage. Moderation/report free text may require individual privacy review.

The private Windows launcher also passed health/auth-challenge, status, pruning and SQLite-backup smoke checks and was stopped afterward. It did not install a persistent service, tunnel or cloud resource. See [community/VERIFICATION.md](../community/VERIFICATION.md) for the exact local setup and its limits.

**The community service is not publicly deployed.** Real Google Credential Manager sign-in, production HTTPS, public uploads, public moderation and deployment recovery remain unverified. Android service configuration stays empty until a real isolated deployment and OAuth clients exist. The local authentication fixtures are not a claim of a live Google login. Operational moderation, a private contact route, hosting details and launch privacy/online-safety review remain prerequisites; see [COMMUNITY.md](COMMUNITY.md) and [PRIVACY.md](PRIVACY.md).

## Signed release and external checks

The final 1.1.0 APK/AAB build and signature verification passed. Official bundletool 1.18.3 validation and APK-set generation also passed, as confirmed by the release operator.

The actual signed 1.1.0 APK was installed and launched with Wi-Fi/mobile data disabled on the isolated API 36 emulator. It displayed ten guides and the native MapLibre map, remained alive, was non-debuggable and recorded no app crash. APK/AAB hashes and structural native alignment are recorded in [RELEASE_BUNDLE_VALIDATION.md](RELEASE_BUNDLE_VALIDATION.md).

**Online pack/weather/location run:** the current-release test is pending. This report does not yet claim a genuine download of the current public pack, a current live forecast/cache round trip or the current build's location flow.

A current-source GitHub CI result will be recorded after publication. The successful local build does not establish Play acceptance.

## Practical limits

Runtime coverage is the dedicated API 36 emulator. Physical devices, other Android versions and devices using 16 KB memory pages have not been exercised for 1.1.0. Structural artifact alignment checks must not be described as a runtime test on such a device.

Solar position, weather-derived drying suggestions and mapped walk-in calculations are estimates. Unknown aspect remains unknown; solar geometry does not model local cliffs, trees or cloud shadow. Automated tests validate the implemented rules and preserved source data, not route safety, surveying accuracy or legal access. No store publication or completed legal-compliance assessment is claimed.
