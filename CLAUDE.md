# CLAUDE.md

Guidance for working in this repository.

## What this is

An Android VR/AR perception experiment (a low-budget "The Machine To Be Another").
It renders the phone's rear camera to the screen in stereo for a Cardboard headset;
tapping the trigger left/right-mirrors the image. Single-module Android app, written
in Java, package `io.github.metavee.machinetobeanother`.

## Build & run

The project uses the Gradle wrapper — no separate Gradle install needed.

```sh
./gradlew assembleDebug     # builds app/build/outputs/apk/debug/app-debug.apk
```

Requires an Android SDK (set `ANDROID_HOME`, accept licenses). If the SDK is missing
in the environment, install the packages CI uses:

```sh
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

There are no unit tests in the project. "Verifying a change builds" means a clean
`./gradlew assembleDebug`.

## Toolchain

- Android Gradle Plugin **8.5.2**, Gradle **8.9**, JDK **17**
- `compileSdk` / `targetSdk` **34**, `minSdk` **21**
- AndroidX (the app was migrated off the legacy `android.support.*` libraries)

## ⚠️ The Google VR SDK is vendored on purpose — do not un-vendor it

The Google VR (Cardboard) SDK is consumed from a **local AAR**:
`app/libs/sdk-base-1.200.0.aar`, wired up via a `flatDir` repository in
`settings.gradle` and referenced by name in `app/build.gradle`.

This is deliberate. The SDK is **no longer hosted on any Maven repository**: it only
ever lived on JCenter, which JFrog has repointed to redirect to Maven Central, where
the artifacts never existed. Google Maven doesn't have it either. **Do not "clean this
up" by replacing the local AAR with a `com.google.vr:sdk-base:<version>` coordinate —
no version resolves remotely and the build will break.** The AAR is a self-contained
fat AAR (bundles its internal deps and native libs for arm64-v8a/armeabi-v7a/x86), so
it needs no transitive Maven dependencies. Jetifier is intentionally *not* enabled —
the AAR references no legacy support-library classes.

## CI / releases

`.github/workflows/android.yml` builds on pushes (to `master`, `main`, and
`claude/**`), PRs, and manual dispatch. There are two paths:

- **Working branches (`claude/**`), PRs, and manual runs** build a **debug** APK.
  Non-PR runs publish it to a rolling **`debug-latest`** GitHub *pre-release*
  (direct-download `.apk`, no zip) and upload it as a zipped workflow artifact.
- **Merges to `main`/`master`** build a **release** APK and publish it as a full
  (non-pre) GitHub **Release** with a stable, versioned tag
  (`v<versionName>-build.<run#>`), marked as the repo's "Latest release".

Publishing needs `contents: write` permission and uses the `gh` CLI.

The release build is signed. Add repository secrets `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` to sign
with a real upload/release key; without them the release build falls back to the
debug signing key (see `app/build.gradle`) so the APK still installs for sideloading.

## Permissions model (modern Android)

Only `CAMERA` is declared, and it's requested at runtime in `MainActivity`. Recordings
are written to the app's own external files dir (`getExternalFilesDir`), which needs no
storage permission. The manifest strips the `READ/WRITE_EXTERNAL_STORAGE` permissions
the VR AAR contributes via `tools:node="remove"`. Every activity sets `android:exported`
explicitly. Keep this minimal set — don't reintroduce storage or phone-state permissions.

## Key files

- `app/src/main/java/.../MainActivity.java` — launcher menu + camera permission request
- `app/src/main/java/.../TextureTestActivity.java` — the GVR stereo renderer (view / record / playback)
- `app/src/main/java/.../VideoListActivity.java` — recorded-video picker
- `app/src/main/java/.../WorldLayoutData.java` — quad geometry + L/R-flip texture coords
- `app/src/main/res/raw/rect_*.glsl` — pass-through OES-texture shaders

## Modernization backlog (not yet done)

The app builds and runs but still leans on deprecated APIs. Durable follow-ups:

- Replace the deprecated `android.hardware.Camera` API with Camera2/CameraX.
- Move off the frozen GVR SDK — current Cardboard SDK, or a small custom stereo renderer —
  so the build no longer depends on a vendored, unmaintained AAR.
