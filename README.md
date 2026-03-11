# QR Code

QR Code is a minimal Android app for Meta Quest 3 and Quest 3S. It scans QR codes with the headset cameras and opens valid `http` or `https` links in the system browser.

## Requirements

- Java 17
- Android SDK 34

## Build

```bash
./gradlew assembleManaged
```

Debug APK:

```bash
./gradlew assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/managed/app-managed.apk
```

## Project layout

- `app/` Android application source
- `gradle/` Gradle wrapper files
- `version.properties` app version values
