# ArborXR pipeline

This project includes a repo-local ArborXR upload pipeline built against the current ArborXR v3 OpenAPI spec.

Official specs:

- JSON: https://api.xrdm.app/api/v3/docs.json
- YAML alias: https://api.xrdm.app/api/v3/docs.yml

## What it does

1. Builds the APK with project-local Gradle state
2. Optionally auto-increments `versionCode` from the current ArborXR release channel
3. Writes the resolved `VERSION_CODE` and `VERSION_NAME` back to `version.properties`
4. Creates a new app version via `POST /apps/{appId}/versions`
5. Requests presigned multipart upload URLs
6. Uploads the APK to ArborXR storage
7. Completes the upload
8. Waits for ArborXR processing to reach `available`
9. Reassigns the target release channel to the new version

## Files

- `scripts/arborxr_upload.py`
- `.env.arborxr.example`
- `version.properties`

## Setup

Copy `.env.arborxr.example` to `.env.arborxr` and fill in at least:

- `ARBORXR_API_KEY`
- `ARBORXR_APP_ID`
- `ARBORXR_RELEASE_CHANNEL_ID`

Current project default:

- App: `QR Scanner`
- App ID: `fe3d45a2-3f36-43bf-b849-bda30f29d2d2`
- Release channel: `Latest`
- Release channel ID: `70132bd2-2676-4d30-adea-b14fae20673b`

## Usage

Build, upload, wait for processing, and move the release channel:

```bash
python3 scripts/arborxr_upload.py
```

Build with explicit version metadata:

```bash
python3 scripts/arborxr_upload.py --version-code 2 --version-name 1.0.1
```

Upload an already-built APK without rebuilding:

```bash
python3 scripts/arborxr_upload.py --skip-build --apk app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- The API key is read from env only and is not stored in source.
- The script uses only Python standard-library modules.
- ArborXR still validates package name, version code, signature, and processing status after upload.
- `versionCode` is treated as the monotonic Android build number and `versionName` as the human-readable release string.
