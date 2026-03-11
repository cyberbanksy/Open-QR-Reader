# Privacy

Quest QR Launch does not collect, transmit, sell, or share personal data.

## What the app accesses

- Headset camera frames, solely in memory, to detect QR codes

## What the app does not do

- No analytics
- No crash reporting
- No account system
- No cloud sync
- No screenshots or recordings
- No local scan history
- No external storage writes
- No network access from the app itself

## Diagnostics

The app emits local `logcat` diagnostics for lifecycle, permission, camera, and browser handoff events. It does not log QR contents, scanned URLs, or user history.

## Data handling

Camera frames are processed in memory and discarded. If a scanned QR code contains a valid `http` or `https` URL, the app launches the external browser and exits. The app does not persist the scanned value.
