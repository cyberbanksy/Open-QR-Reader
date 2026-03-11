#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import error, request


DEFAULT_API_BASE = "https://api.xrdm.app/api/v3"
MAX_MULTIPARTS = 4
DEFAULT_POLL_SECONDS = 5
DEFAULT_TIMEOUT_SECONDS = 900
VERSION_FILE = Path("version.properties")
DEFAULT_APK_PATH = "app/build/outputs/apk/managed/app-managed.apk"
DEFAULT_GRADLE_TASK = "assembleManaged"
DEFAULT_EXPECTED_PACKAGE = "com.zephyr.qr.debug"
DEFAULT_PREFLIGHT_SCRIPT = Path("scripts/quest_vrc_preflight.py")


class ArborXRError(RuntimeError):
    pass


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def env_or_error(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ArborXRError(f"Missing required environment variable: {name}")
    return value


def maybe_env(name: str) -> str | None:
    value = os.environ.get(name, "").strip()
    return value or None


def read_version_file(path: Path = VERSION_FILE) -> tuple[int, str]:
    if not path.exists():
        raise ArborXRError(f"Missing version file: {path}")
    values: dict[str, str] = {}
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    try:
        return int(values["VERSION_CODE"]), values["VERSION_NAME"]
    except KeyError as exc:
        raise ArborXRError(f"Invalid version file: {path}") from exc


def write_version_file(version_code: int, version_name: str, path: Path = VERSION_FILE) -> None:
    path.write_text(f"VERSION_CODE={version_code}\nVERSION_NAME={version_name}\n")


@dataclass
class ArborXRClient:
    api_key: str
    api_base: str

    def json_request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> Any:
        url = f"{self.api_base}{path}"
        data = None
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)

        req = request.Request(url, data=data, headers=headers, method=method)
        try:
            with request.urlopen(req) as response:
                body = response.read()
                if not body:
                    return None
                return json.loads(body.decode("utf-8"))
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise ArborXRError(f"{method} {url} failed: {exc.code} {body}") from exc

    def upload_part(self, url: str, payload: bytes) -> str:
        req = request.Request(
            url,
            data=payload,
            headers={
                "Content-Length": str(len(payload)),
                "Content-Type": "application/octet-stream",
            },
            method="PUT",
        )
        try:
            with request.urlopen(req) as response:
                etag = response.headers.get("ETag")
                if not etag:
                    raise ArborXRError("Missing ETag from ArborXR presigned upload response")
                return etag.strip('"')
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise ArborXRError(f"PUT {url} failed: {exc.code} {body}") from exc


def run_build(gradle_task: str, version_code: int | None, version_name: str | None) -> None:
    command = ["./gradlew", gradle_task]
    if version_code is not None:
        command.append(f"-PappVersionCode={version_code}")
    if version_name:
        command.append(f"-PappVersionName={version_name}")
    subprocess.run(command, check=True)


def run_preflight(apk_path: Path, expected_package: str, script_path: Path) -> None:
    subprocess.run(
        [
            sys.executable,
            str(script_path),
            "--apk",
            str(apk_path),
            "--expected-package",
            expected_package,
        ],
        check=True,
    )


def read_release_channel_version_code(client: ArborXRClient, app_id: str, release_channel_id: str) -> int | None:
    payload = client.json_request("GET", f"/apps/{app_id}/release-channels/{release_channel_id}")
    version = payload.get("version") or {}
    code = version.get("code")
    return int(code) if code is not None else None


def choose_part_size(file_size: int) -> int:
    part_count = max(1, min(MAX_MULTIPARTS, math.ceil(file_size / (32 * 1024 * 1024))))
    return math.ceil(file_size / part_count)


def chunk_file(file_path: Path, part_size: int) -> list[tuple[int, bytes]]:
    parts: list[tuple[int, bytes]] = []
    with file_path.open("rb") as handle:
        part_number = 1
        while True:
            chunk = handle.read(part_size)
            if not chunk:
                break
            parts.append((part_number, chunk))
            part_number += 1
    return parts


def find_version(client: ArborXRClient, app_id: str, version_id: str) -> dict[str, Any] | None:
    payload = client.json_request("GET", f"/apps/{app_id}/versions?per_page=100&page=1")
    for version in payload.get("data", []):
        if version.get("id") == version_id:
            return version
    return None


def wait_for_version(client: ArborXRClient, app_id: str, version_id: str, timeout_seconds: int) -> dict[str, Any]:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        version = find_version(client, app_id, version_id)
        if version:
            status = version.get("status")
            print(f"ArborXR processing status: {status}")
            if status == "available":
                return version
            if status == "error":
                raise ArborXRError(f"ArborXR marked version {version_id} as error")
        time.sleep(DEFAULT_POLL_SECONDS)
    raise ArborXRError(f"Timed out waiting for version {version_id} to become available")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build and upload an ArborXR app update.")
    parser.add_argument("--env-file", default=".env.arborxr", help="Project-local env file to load first.")
    parser.add_argument("--api-base", default=None, help="Override ArborXR API base URL.")
    parser.add_argument("--app-id", default=None, help="ArborXR app ID.")
    parser.add_argument("--release-channel-id", default=None, help="ArborXR release channel ID.")
    parser.add_argument("--new-release-channel-name", default=None, help="Create a new release channel during upload.")
    parser.add_argument("--apk", default=None, help="APK path to upload.")
    parser.add_argument("--gradle-task", default=None, help="Gradle task to build before upload.")
    parser.add_argument("--version-code", type=int, default=None, help="Override Android versionCode for this build.")
    parser.add_argument("--version-name", default=None, help="Override Android versionName for this build.")
    parser.add_argument("--release-notes", default=None, help="Release notes sent to ArborXR.")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--expected-package", default=None, help="Expected Android package name for the APK.")
    parser.add_argument("--preflight-script", default=str(DEFAULT_PREFLIGHT_SCRIPT), help="Package-level preflight script to run before upload.")
    parser.add_argument("--skip-build", action="store_true", help="Upload an existing APK instead of building first.")
    parser.add_argument("--skip-preflight", action="store_true", help="Skip local Quest package preflight checks.")
    parser.add_argument("--skip-channel-update", action="store_true", help="Do not reassign the release channel after upload.")
    parser.add_argument("--no-write-version-file", action="store_true", help="Do not persist resolved version values to version.properties.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    load_env_file(Path(args.env_file))

    api_key = env_or_error("ARBORXR_API_KEY")
    api_base = args.api_base or maybe_env("ARBORXR_API_BASE") or DEFAULT_API_BASE
    app_id = args.app_id or maybe_env("ARBORXR_APP_ID")
    release_channel_id = args.release_channel_id or maybe_env("ARBORXR_RELEASE_CHANNEL_ID")
    new_release_channel_name = args.new_release_channel_name or maybe_env("ARBORXR_NEW_RELEASE_CHANNEL_NAME")
    apk_path = Path(args.apk or maybe_env("ARBORXR_APK_PATH") or DEFAULT_APK_PATH)
    gradle_task = args.gradle_task or maybe_env("ARBORXR_GRADLE_TASK") or DEFAULT_GRADLE_TASK
    version_name = args.version_name or maybe_env("APP_VERSION_NAME")
    version_code = args.version_code
    release_notes = args.release_notes if args.release_notes is not None else maybe_env("ARBORXR_RELEASE_NOTES")
    expected_package = args.expected_package or maybe_env("ARBORXR_EXPECTED_PACKAGE") or DEFAULT_EXPECTED_PACKAGE
    preflight_script = Path(args.preflight_script)

    if not app_id:
        raise ArborXRError("Missing app ID. Provide --app-id or ARBORXR_APP_ID.")

    client = ArborXRClient(api_key=api_key, api_base=api_base)
    file_version_code, file_version_name = read_version_file()

    if version_code is None and release_channel_id:
        current_code = read_release_channel_version_code(client, app_id, release_channel_id)
        if current_code is not None:
            version_code = current_code + 1
            print(f"Auto-incremented versionCode to {version_code}")

    if version_code is None:
        version_code = file_version_code
    if version_name is None:
        version_name = file_version_name

    if not args.no_write_version_file:
        write_version_file(version_code, version_name)
        print(f"Updated {VERSION_FILE} to VERSION_CODE={version_code}, VERSION_NAME={version_name}")

    if not args.skip_build:
        print(f"Building APK with Gradle task {gradle_task}")
        run_build(gradle_task=gradle_task, version_code=version_code, version_name=version_name)

    if not apk_path.exists():
        raise ArborXRError(f"APK not found: {apk_path}")

    if not args.skip_preflight:
        print(f"Running Quest package preflight via {preflight_script}")
        run_preflight(apk_path=apk_path, expected_package=expected_package, script_path=preflight_script)

    file_size = apk_path.stat().st_size
    part_size = choose_part_size(file_size)
    parts = chunk_file(apk_path, part_size)
    print(f"Uploading {apk_path} in {len(parts)} part(s)")

    initiate_payload: dict[str, Any] = {"filename": apk_path.name}
    if release_channel_id:
        initiate_payload["releaseChannelId"] = release_channel_id
    elif new_release_channel_name:
        initiate_payload["newReleaseChannelTitle"] = new_release_channel_name

    initiated = client.json_request("POST", f"/apps/{app_id}/versions", initiate_payload)
    upload_id = initiated["uploadId"]
    key = initiated["key"]
    version_id = initiated["versionId"]
    print(f"Created ArborXR version {version_id}")

    presigned = client.json_request(
        "POST",
        f"/apps/{app_id}/versions/{version_id}/pre-sign",
        {
            "key": key,
            "uploadId": upload_id,
            "partNumbers": [part_number for part_number, _ in parts],
        },
    )

    url_by_part = {
        item["partNumber"]: item.get("url") or item.get("presignedUrl")
        for item in presigned
    }

    completed_parts = []
    for part_number, payload in parts:
        print(f"Uploading part {part_number}/{len(parts)}")
        url = url_by_part.get(part_number)
        if not url:
            raise ArborXRError(f"Missing presigned URL for part {part_number}")
        etag = client.upload_part(url, payload)
        completed_parts.append({"partNumber": part_number, "eTag": etag})

    complete_payload: dict[str, Any] = {
        "key": key,
        "uploadId": upload_id,
        "parts": completed_parts,
    }
    if version_name:
        complete_payload["versionName"] = version_name
    if release_notes:
        complete_payload["releaseNotes"] = release_notes

    client.json_request("POST", f"/apps/{app_id}/versions/{version_id}/complete", complete_payload)
    version = wait_for_version(client, app_id, version_id, args.timeout_seconds)

    if release_channel_id and not args.skip_channel_update:
        client.json_request(
            "PUT",
            f"/apps/{app_id}/release-channels/{release_channel_id}",
            {"versionId": version_id},
        )
        print(f"Updated release channel {release_channel_id} to version {version_id}")

    print("Upload complete")
    print(json.dumps(
        {
            "versionId": version_id,
            "status": version.get("status"),
            "version": version.get("version"),
            "code": version.get("code"),
            "sizeBytes": version.get("sizeBytes"),
        },
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ArborXRError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
