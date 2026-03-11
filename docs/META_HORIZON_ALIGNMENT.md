# Meta Horizon alignment

Reviewed against official Meta documentation on March 10, 2026.

## What this app is

- Android app on Horizon OS
- Java/Kotlin `Camera2` passthrough access
- QR utility that hands supported links to the external browser

## What this app is not

- Immersive OpenXR app
- Native renderer that submits XR frames
- In-app browser

## Why

Meta's current passthrough camera documentation explicitly supports Java/Kotlin `Camera2` access for Quest 3 and Quest 3s, with `minSdk 34`, Horizon OS v76+, and the headset camera permission. That matches this scanner directly.

Relevant docs:

- https://developers.meta.com/horizon/documentation/native/android/pca-native-documentation/
- https://developers.meta.com/horizon/discover/2d-apps-meta-spatial/

By contrast, Meta's immersive native manifest guidance applies to OpenXR-style apps that actually render immersive content. When this project was temporarily launched that way, Quest showed the three-dot loader because the shell expected immersive frame submission from an app that is only an Android activity.

## Practical result

The current manifest intentionally uses:

- `android.intent.category.LAUNCHER`
- `android.hardware.camera2.any`
- `com.oculus.supportedDevices=quest3|quest3s`
- `android.permission.CAMERA`
- `horizonos.permission.HEADSET_CAMERA`

It intentionally does not use immersive native launch declarations like `com.oculus.intent.category.VR` or `android.hardware.vr.headtracking`, because those are for a different app model.
