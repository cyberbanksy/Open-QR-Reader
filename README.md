# OpenQR

A lightweight QR code reader for Meta Quest 3 and Quest 3S.

OpenQR is a native Android app built for quick QR scanning inside a headset. It uses the Quest passthrough camera, detects QR codes locally with ZXing, and opens valid `http` and `https` links in the system browser.

This repository is source-only. There are no packaged APKs, hosted services, or upload steps here. Clone it, build it, and install it yourself.

## Highlights

- Native Android app written in Kotlin
- Built for Meta Quest 3 and Quest 3S
- Local QR detection with ZXing
- Opens web links in the external browser
- No backend, account system, or cloud dependency

## Supported Devices

- Meta Quest 3
- Meta Quest 3S

The app depends on headset camera access and targets Quest passthrough-capable devices.

## Build

### Requirements

- Java 17
- Android SDK 34
- Android Studio, or a working local Android/Gradle setup

### Debug build

```bash
./gradlew assembleDebug
```

### Managed build

```bash
./gradlew assembleManaged
```

## Project Structure

```text
app/        Android app source, resources, and tests
gradle/     Gradle wrapper files
```

## Privacy

OpenQR processes camera frames on-device for QR detection. It does not require a server to scan codes.
