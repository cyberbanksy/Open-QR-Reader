# Quest QR Launch

Quest QR Launch is a lightweight open-source Meta Quest QR scanner for Quest 3 and Quest 3s. It scans a QR code with the headset passthrough cameras and immediately hands off valid `http`/`https` links to the system browser instead of rendering pages inside the app.

## Design goals

- Immediate external browser handoff
- No analytics, tracking, or user-data logging
- No URL history, screenshots, or storage writes
- Minimal permission surface
- Quest-specific manifest settings aligned with current Meta documentation

## Platform scope

- Meta Quest 3 / Quest 3s
- Horizon OS v76 or newer
- Android `minSdk 34`

The current Meta passthrough camera documentation for Android native Camera2 access states that Camera2 passthrough support is for Quest 3 and Quest 3s, the sample uses `minSdk 34`, and Horizon OS v76+ is required:

- [Meta passthrough camera Camera2 docs](https://developers.meta.com/horizon/documentation/native/android/pca-native-documentation/)
- [Meta Android manifest settings](https://developers.meta.com/horizon/documentation/native/android/mobile-native-manifest/)

## Open-source dependencies

- [ZXing core](https://github.com/zxing/zxing) for QR decoding, Apache-2.0
- AndroidX libraries from Google

## Security and privacy

- The app only requests `android.permission.CAMERA` and `horizonos.permission.HEADSET_CAMERA`.
- Only absolute `http` and `https` URLs are accepted for browser launch.
- URLs using `javascript:`, `intent:`, `file:`, or missing hosts are rejected.
- No logs are emitted containing scanned content.
- No network permission is declared.
- Android backups are disabled.

## Build

1. Ensure Android SDK platform 34 is installed.
2. Run `./gradlew assembleDebug`
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Project-local state

The wrapper is configured to keep Gradle caches and downloaded build dependencies in `./.gradle-home` by default so build state stays inside the repo as much as possible.

Still external by necessity:

- Android SDK / `sdkmanager`
- JDK
- `adb`

## Suggested checks

- `./gradlew test`
- `./gradlew lint`
- `./gradlew assembleDebug`

## Store review notes

This project intentionally keeps the manifest narrow:

- `android.hardware.vr.headtracking`
- `android.hardware.camera2.any`
- `com.oculus.supportedDevices=quest3|quest3s`
- `android.permission.CAMERA`
- `horizonos.permission.HEADSET_CAMERA`

If you plan to submit to the Meta Horizon Store, you should still run the current Meta review tooling and confirm the latest permission review language in the developer dashboard at submission time.
