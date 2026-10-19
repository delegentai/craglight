# CragLight 1.1.0 release validation

Verified 19 October 2026 using the isolated native Windows toolchain. No signing material is included in public files.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `CragLight-1.1.0.apk` | 76,772,461 | `58b317d5f391cd3cd525ab146d214a5975e390bce3424fc24f2277f5aeddb3e3` |
| `CragLight-1.1.0.aab` | 49,342,662 | `40522317a49ec7720537f110b43dcb88502a097083f066196e6caeb368a8f51c` |

The APK v2 signature verifies with the existing independent CragLight release certificate, SHA-256 `424a616906bcc8a97c412d89189a522d98216a1bc1379b9f980cee5e5fca28c6` (`CN=CragLight Android Release`). Package `org.craglight.app`, version code 2, minimum API 26 and target API 36 were verified. The non-debuggable signed APK was installed and launched offline on the dedicated API 36 emulator. Ten crag guides and the native MapLibre map were visible; the process remained alive without an app crash in the crash log.

The AAB JAR signature verifies. Official bundletool 1.18.3 validation and universal APK generation pass. The generated validation APK is unsigned and is neither installed nor published. Bundle configuration uses `PAGE_ALIGNMENT_16K`; both the signed APK and bundle-generated APK have 16 KB-aligned uncompressed native ZIP offsets and at least 16 KB-aligned load segments for every packaged 64-bit ELF library. These are structural checks, not a runtime test on a device using 16 KB pages.

The JDK streaming-signature warning remains explained by ZIP order: the AAB manifest is entry 688 of 689, with no duplicate names or CRC errors. Random-access cryptographic verification succeeds; a streaming JarInputStream expects its manifest near the beginning. Self-signed certificate/timestamp warnings do not indicate a failed signature. The original AGP output is retained.

Reproduce with `scripts/verify-release.ps1`, `tools/test-release-emulator.ps1`, and official [bundletool](https://developer.android.com/tools/bundletool) `validate` / `build-apks --mode=universal` against the release AAB. Local evidence under `evidence/release/` includes signature, package, ZIP-order, bundle configuration, bundletool, ELF/ZIP alignment, emulator results and screenshots. `docs/QA.md` describes test coverage and limits.

The online community is not configured in this build. No Play Console acceptance, public Google login or hosted community deployment is claimed.
