# Ensure the CI workspace contains the Sherpa-ONNX Android AAR before Gradle resolves it.

Background
----------
`.github/workflows/build.yml` runs on checkout of `dvex-integration` and executes:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon

`app/build.gradle.kts` declares:

    implementation("com.k2fsa:sherpa-onnx-android:1.13.8")

`settings.gradle.kts` resolves that dependency from the project-local Maven mirror at
`<rootDir>/repo`:

    maven { url = uri(rootDir.resolve("repo")) }

The `repo/` directory holds the official release AAR for `v1.13.8`:

- `repo/com/k2fsa/sherpa-onnx-android/1.13.8/sherpa-onnx-android-1.13.8.aar`
- `repo/com/k2fsa/sherpa-onnx-android/1.13.8/sherpa-onnx-android-1.13.8.jar`
- `repo/com/k2fsa/sherpa-onnx-android/1.13.8/sherpa-onnx-android-1.13.8.pom`

Verification
------------
- AAR SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- Checked against the official GitHub release asset by direct download; the SHA-256
  matches. See `app/build.gradle.kts` for the same checksum in the in-file comment.

Previous CI failures
--------------------
- `Could not find com.k2fsa:sherpa-onnx-android:1.13.8`
  because `repo/` was present only in the working tree, not committed into the
  `dvex-integration` branch — so `actions/checkout@v4` never placed it in the
  CI workspace.

Safety
------
- This commit adds only the versioned Sherpa-ONNX Android AAR and its POM/JAR to
  `repo/`. It does not change `app/`, `settings.gradle.kts`, or any Kotlin source.
- Wake-word functionality (`SherpaOnnxWakeWordDetector`, `KeywordSpotter`, the KWS
  model assets under `app/src/main/assets/kws/`, and the native `.so` wrappers under
  `app/src/main/jniLibs/`) are untouched.
- No API keys, secrets, or credentials are included.
