# CLAUDE.md

Guidance for working in this repository.

## What this is

An Android VR/AR perception experiment (a low-budget "The Machine To Be Another").
It renders the phone's rear camera to the screen in stereo for a Cardboard headset;
tapping the trigger (a screen tap) left/right-mirrors the image. Stereo is drawn by a
small in-app renderer whose per-eye geometry comes from a Cardboard viewer profile, so
different headsets can be calibrated (see the VR-rendering section below). Single-module
Android app, written in Java, package `io.github.metavee.machinetobeanother`.

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

## VR rendering & viewer calibration (no VR SDK)

The app used to render stereo through the deprecated Google VR (GVR/Cardboard) SDK,
consumed from a vendored fat AAR. **That dependency has been removed** — there is no
VR SDK anymore, and no `app/libs` AAR. Do **not** re-introduce `com.google.vr:sdk-base`
(it resolves from no Maven repo) or the current NDK Cardboard SDK unless there's a
concrete need; stereo is handled in-app.

How it works now:

- `TextureTestActivity` hosts a plain `GLSurfaceView` and implements
  `GLSurfaceView.Renderer` itself. Each frame it draws the camera passthrough quad once
  per eye into the left/right half of the surface. There is intentionally **no head
  tracking** — the image is pinned in front of the viewer, matching the original app.
- Per-eye projection comes from a **Cardboard viewer profile** (`CardboardProfile`): the
  same lens/screen geometry the official Cardboard app uses, encoded in the QR code on a
  headset (`https://google.com/cardboard/cfg?p=<base64 DeviceParams protobuf>`).
  `CardboardProfile` parses that protobuf with a tiny hand-rolled wire reader (no
  protobuf runtime dependency), persists the raw bytes in `SharedPreferences`, and
  computes an asymmetric frustum per eye so each eye's image is centered under its lens
  and scaled to the headset. A built-in Cardboard v2 default is used until one is saved.
- Calibration input is currently **manual**: the "Calibrate viewer" button in
  `MainActivity` prompts for the profile URL and saves it. A **QR short link** (e.g.
  `goo.gl/…`) can be pasted directly — `MainActivity#resolveDeviceParams` follows the HTTP
  redirects on a background thread until it reaches the `cfg?p=` URL (stopping before the
  "get Cardboard" landing page). (A camera-based QR scanner is a deliberate future
  enhancement — see the backlog.)
- Lens **barrel distortion** is applied by `DistortionRenderer`: each eye is rendered to
  an off-screen FBO, then drawn to the screen through a pre-distorted mesh built from the
  profile's `distortion_coefficients` (Cardboard's `r*(1+k1*r²+k2*r⁴)` model, inverted
  per mesh vertex). With zero coefficients it degrades to an identity blit. The distortion
  math has been verified to build but should be **eyeballed on a real headset** and tuned
  if needed — it wasn't validated on-device.

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

Two permissions are declared: `CAMERA` (requested at runtime in `MainActivity`) and
`INTERNET`. INTERNET is used **only** to follow a calibration short link's redirects to
the underlying profile URL (`MainActivity#resolveDeviceParams`). Recordings are written
to the app's own external files dir (`getExternalFilesDir`), which needs no storage
permission. (The `READ/WRITE_EXTERNAL_STORAGE` strips that used to counter the VR AAR's
manifest contributions are gone with the AAR.) Every activity sets `android:exported`
explicitly. Keep this minimal set — don't reintroduce storage or phone-state permissions.

## Key files

- `app/src/main/java/.../MainActivity.java` — launcher menu + camera permission + "Calibrate viewer" URL entry
- `app/src/main/java/.../TextureTestActivity.java` — the custom GLSurfaceView stereo renderer (view / record / playback)
- `app/src/main/java/.../CardboardProfile.java` — Cardboard viewer profile: QR/protobuf parse, persistence, per-eye frustum
- `app/src/main/java/.../VideoListActivity.java` — recorded-video picker
- `app/src/main/java/.../WorldLayoutData.java` — quad geometry + L/R-flip texture coords
- `app/src/main/res/raw/rect_*.glsl` — pass-through OES-texture shaders

## Modernization backlog (not yet done)

The app builds and runs but still leans on deprecated APIs. Durable follow-ups:

- Replace the deprecated `android.hardware.Camera` API with Camera2/CameraX.
- Add an in-app **QR scanner** for calibration so users can scan a viewer's code directly
  instead of pasting its URL (e.g. ZXing, which needs minSdk 24, or ML Kit / Google Code
  Scanner).
- **Verify/tune the lens distortion on a real headset.** `DistortionRenderer` builds and
  is correct in principle, but its output has not been eyeballed on-device; confirm lines
  look straight through the lenses and adjust if the model/coefficients need refining.
