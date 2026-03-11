#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


class PreflightError(RuntimeError):
    pass


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise PreflightError(f"Required tool not found on PATH: {name}")
    return path


def run_command(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exc:
        raise PreflightError(
            f"Command failed: {' '.join(command)}\n{exc.stderr.strip()}"
        ) from exc
    return result.stdout


def parse_badging(badging: str) -> tuple[str, set[str]]:
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    package_match = re.search(r"name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", package_line)
    if not package_match:
        raise PreflightError("Unable to parse package metadata from aapt output")

    permissions = {
        match.group(1)
        for match in re.finditer(r"uses-permission: name='([^']+)'", badging)
    }
    return package_match.group(1), permissions


def locate_apksigner() -> str:
    direct = shutil.which("apksigner")
    if direct:
        return direct

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise PreflightError("apksigner was not found and ANDROID_HOME/ANDROID_SDK_ROOT is not set")

    build_tools_root = Path(android_home) / "build-tools"
    candidates = sorted(build_tools_root.glob("*/apksigner"))
    if not candidates:
        raise PreflightError("Unable to find apksigner under Android build-tools")
    return str(candidates[-1])


def main() -> int:
    parser = argparse.ArgumentParser(description="Run package-level Quest VRC preflight checks.")
    parser.add_argument("--apk", required=True, help="APK path to inspect")
    parser.add_argument("--expected-package", required=True, help="Expected package name")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    if not apk_path.exists():
        raise PreflightError(f"APK not found: {apk_path}")

    require_tool("aapt")
    badging = run_command(["aapt", "dump", "badging", str(apk_path)])
    package_name, permissions = parse_badging(badging)

    if package_name != args.expected_package:
        raise PreflightError(f"Package mismatch: expected {args.expected_package}, found {package_name}")

    if "application-debuggable" in badging:
        raise PreflightError("APK is debuggable; release-style managed builds must not set application-debuggable")

    if apk_path.stat().st_size >= 1024 * 1024 * 1024:
        raise PreflightError("APK exceeds 1 GB packaging limit")

    allowed_permissions = {
        "android.permission.CAMERA",
        "horizonos.permission.HEADSET_CAMERA",
        f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    unexpected_permissions = sorted(permission for permission in permissions if permission not in allowed_permissions)
    if unexpected_permissions:
        raise PreflightError(f"Unexpected permissions present: {', '.join(unexpected_permissions)}")

    sdk_line = next((line for line in badging.splitlines() if line.startswith("sdkVersion:")), "")
    target_line = next((line for line in badging.splitlines() if line.startswith("targetSdkVersion:")), "")
    if sdk_line != "sdkVersion:'34'":
        raise PreflightError(f"Unexpected minSdkVersion: {sdk_line or 'missing'}")
    if target_line != "targetSdkVersion:'34'":
        raise PreflightError(f"Unexpected targetSdkVersion: {target_line or 'missing'}")

    native_line = next((line for line in badging.splitlines() if line.startswith("native-code:")), "")
    if native_line and "arm64-v8a" not in native_line:
        raise PreflightError(f"Native code is missing arm64-v8a support: {native_line}")

    if "application-icon-" not in badging:
        raise PreflightError("APK does not expose raster launcher icons")

    apksigner = locate_apksigner()
    verify_output = run_command([apksigner, "verify", "--verbose", str(apk_path)])
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in verify_output:
        raise PreflightError("APK is not signed with APK Signature Scheme v2")

    print("Quest VRC package preflight passed")
    print(f"package={package_name}")
    print(f"size_bytes={apk_path.stat().st_size}")
    print(f"permissions={','.join(sorted(permissions))}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PreflightError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
