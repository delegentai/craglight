# Offline third-party notices

The app's **Licences and credits** dialog reads `app/src/main/assets/licenses/index.json` and its bundled UTF-8 text files. No internet request is made by the dialog. URLs in source records remain available as selectable text.

The distribution includes:

- Apache License 2.0 and the original licence/NOTICE files found in the resolved runtime/developer AAR and JAR snapshot, deduplicated only when their bytes are identical.
- [MapLibre Android 13.7.1 consolidated notice](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/platform/android/LICENSE.md), including BSD terms and its native dependency licence collection. This vendor document is kept verbatim; it includes notices for more platforms than this Android OpenGL build uses.
- [MapLibre Gestures 0.0.4 notice](https://github.com/maplibre/maplibre-gestures-android/blob/0.0.4/LICENSE.md), including the Android Gesture Detectors Framework copyright notice.
- Google Identity and Play-services vendor notices, extracted verbatim from their AAR byte-span ledgers with component associations and Android SDK terms; these are not relabelled as Apache-licensed.
- Kotlin, coroutines and serialization notices; the resolved component/version inventory identifies the dependency versions actually used in the build model.
- OkHttp's original Public Suffix List notice, its source availability URL and the complete Mozilla Public License 2.0.
- The original OS Open Names notice, general source attribution and all ten bundled Multi-pitch.com photograph credits.

`sources.json` records the SHA-256 hash and upstream URL for each displayed text. Native SDK libraries are unmodified. Generated data and rendered photo overlays describe their transformations separately; the app's software licence does not replace any data or photograph licence.

When updating dependencies, regenerate the build dependency inventory, inspect the new AAR/JAR distributions for added NOTICE files, and refresh version-pinned vendor notices. Review the result before shipping. The debug inventory deliberately includes optional tools, which are omitted from the release runtime. The separate AndroidX licence text is the original text shipped in its public artifacts; identical copies are grouped with every artifact association retained.
