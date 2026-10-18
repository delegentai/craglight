# Release bundle validation

Verified on 18 October 2026 using isolated native Windows tools. The original release APK and AAB were not modified by these checks.

| Artifact | SHA256 |
|---|---|
| `release/CragLight-1.0.0.apk` | `f0e4ea6f90ab0cc4cf75f4173d9817d4fc46a1610af29daa03c73e8cf68a3050` |
| `release/CragLight-1.0.0.aab` | `70a1cb5696fcbc80860d42cafc0242bd297ef75d8fa80b03fc8cab1ea16a289e` |

The APK's v2 signature verifies with certificate SHA256 `424a616906bcc8a97c412d89189a522d98216a1bc1379b9f980cee5e5fca28c6` (`CN=CragLight Android Release`). It is non-debuggable, has minimum API 26 and target API 36, and was actually installed and opened offline on the dedicated API 36 emulator. The catalogue contained 828 crags; the native map and cluster counts rendered correctly and no app crash was recorded.

## AAB format, signature and native libraries

- `jarsigner -verify -verbose -certs` reports `jar verified`; the random-access `JarFile` signature verification succeeds.
- Official bundletool 1.18.3 `validate` succeeds.
- Official bundletool 1.18.3 `build-apks --mode=universal` succeeds. This independent conversion produced an **unsigned validation APK**, which was not installed or published.
- Bundle configuration enables uncompressed native libraries with `PAGE_ALIGNMENT_16K`.
- Both the shipped signed APK and the bundletool-generated unsigned APK pass the custom ELF/ZIP inspection: every packaged 64-bit ELF load segment is aligned to at least 16,384 bytes and every uncompressed native-library ZIP data offset is 16 KB aligned. The 32-bit ELF load segments do not require the 64-bit 16 KB page setting.

### JDK streaming-signature warning

The signed AAB contains 251 ZIP entries. `META-INF/MANIFEST.MF` is physically last, at zero-based index 250. There are no duplicate entry names or CRC errors. OpenJDK's [`JarInputStream` implementation](https://raw.githubusercontent.com/openjdk/jdk17u/master/src/java.base/share/classes/java/util/jar/JarInputStream.java) recognizes the manifest only at the start of the stream, optionally after a `META-INF/` directory entry. It therefore cannot initialize streaming signature verification for this entry order and emits warnings comparing `JarInputStream` with `JarFile`. This explains the warning without indicating a failed random-access cryptographic verification.

The other JDK warnings describe the locally generated self-signed certificate, absence of a timestamp, and ignored POSIX ZIP permissions. The signing certificate expires in 2054. The valid original AGP output was retained. Bundletool validation and APK conversion establish local bundle validity; they do not assert that Play Console has accepted or published this app. [Android's bundletool documentation](https://developer.android.com/tools/bundletool) describes local conversion as a way to test the APK generation used by Google Play.

## Reproduction and evidence

With JDK 17 on `PATH`, run from the repository root after downloading [official bundletool 1.18.3](https://github.com/google/bundletool/releases/tag/1.18.3) into a local `tools` directory. The downloaded CLI archive was 32,520,401 bytes and matched its release-asset SHA-256 digest `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`.

```powershell
$bundleTool = Join-Path $PWD 'tools/bundletool-all-1.18.3.jar'
$bundlePath = Join-Path $PWD 'release/CragLight-1.0.0.aab'
java -jar $bundleTool validate --bundle=$bundlePath
java -jar $bundleTool dump config --bundle=$bundlePath
java -jar $bundleTool build-apks --bundle=$bundlePath --output=release/craglight-aab-validation.apks --mode=universal --overwrite
```

Local evidence is retained under `evidence/release/`: `apk-signature.txt`, `aab-signature.txt`, `apk-alignment.json`, `aab-zip-order.json`, `bundletool-validate.txt`, `bundle-config.txt`, `bundletool-build-apks.txt`, `bundletool-apk-alignment.json`, `emulator-result.json`, `release-explore.png`, and `release-map.png`. `evidence/release-verification.txt` also contains the shipped APK's alignment inspection. Machine-specific evidence and toolchain notes remain local and are excluded from the public publishing kit.
