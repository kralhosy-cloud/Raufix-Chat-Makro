#!/usr/bin/env python3
"""Pins the Fabric versions in gradle.properties to what actually exists.

The repository ships `fabric_version=0.0.0+1.21.11` as a placeholder, because the
machine it was authored on could not reach Fabric's Maven. This script is what
replaces that placeholder with a real coordinate, and it refuses to guess:

  * yarn mappings      <- https://meta.fabricmc.net/v2/versions/yarn/<mc>
  * fabric-loader      <- https://meta.fabricmc.net/v2/versions/loader/<mc>
  * fabric-api         <- https://maven.fabricmc.net/.../fabric-api/maven-metadata.xml
                          filtered to the builds that end in +<mc>
  * fabric-loom        <- https://maven.fabricmc.net/.../fabric-loom/maven-metadata.xml

Every value it writes is one it read from the network, and it prints the URL it
came from. If a source is unreachable or yields nothing, it exits non-zero rather
than leaving a placeholder behind.

Usage:
  python3 scripts/resolve-fabric-versions.py [--mc 1.21.11] [--check]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROPS = ROOT / "gradle.properties"

META = "https://meta.fabricmc.net/v2/versions"
MAVEN = "https://maven.fabricmc.net"


def get(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "chat-macro-studio-build"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def get_json(url: str):
    return json.loads(get(url).decode("utf-8"))


def maven_versions(group_path: str, artifact: str) -> list[str]:
    url = f"{MAVEN}/{group_path}/{artifact}/maven-metadata.xml"
    root = ET.fromstring(get(url))
    versions = [node.text.strip() for node in root.iter("version") if node.text]
    print(f"  {len(versions):4d} versions from {url}")
    return versions


def version_key(value: str) -> tuple:
    """Sortable key for versions like 0.141.3+1.21.11 or 1.12.4-SNAPSHOT."""
    core, _, suffix = value.partition("+")
    parts = []
    for piece in re.split(r"[.\-]", core):
        parts.append((0, int(piece)) if piece.isdigit() else (-1, 0))
    return (parts, suffix)


def latest(versions: list[str]) -> str:
    return max(versions, key=version_key)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc", default=None, help="Minecraft version (default: from gradle.properties)")
    parser.add_argument("--check", action="store_true", help="resolve and print, change nothing")
    args = parser.parse_args()

    props = {}
    order = []
    for line in PROPS.read_text(encoding="utf-8").splitlines():
        order.append(line)
        if "=" in line and not line.strip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()

    mc = args.mc or props["minecraft_version"]
    print(f"resolving Fabric coordinates for Minecraft {mc}")

    failures: list[str] = []

    # --- yarn mappings -------------------------------------------------------
    yarn_versions: list[str] = []
    try:
        data = get_json(f"{META}/yarn/{mc}")
        yarn_versions = [entry["version"] for entry in data if entry.get("version")]
        print(f"  {len(yarn_versions):4d} yarn builds from {META}/yarn/{mc}")
    except Exception as exc:  # noqa: BLE001
        failures.append(f"yarn mappings: {exc}")

    # --- loader --------------------------------------------------------------
    loader_versions: list[str] = []
    loader_stable: str | None = None
    try:
        data = get_json(f"{META}/loader/{mc}")
        loader_versions = [entry["loader"]["version"] for entry in data]
        stable = [entry["loader"]["version"] for entry in data if entry["loader"].get("stable")]
        loader_stable = stable[0] if stable else None
        print(f"  {len(loader_versions):4d} loader builds for {mc}, newest stable={loader_stable}")
    except Exception as exc:  # noqa: BLE001
        failures.append(f"fabric-loader: {exc}")

    # --- fabric api ----------------------------------------------------------
    api_versions: list[str] = []
    try:
        api_versions = [v for v in maven_versions("net/fabricmc/fabric-api", "fabric-api")
                        if v.endswith(f"+{mc}")]
        print(f"  {len(api_versions):4d} fabric-api builds for {mc}")
    except Exception as exc:  # noqa: BLE001
        failures.append(f"fabric-api: {exc}")

    # --- loom ----------------------------------------------------------------
    loom_versions: list[str] = []
    try:
        loom_versions = [v for v in maven_versions("net/fabricmc", "fabric-loom")
                         if not v.endswith("-SNAPSHOT") and "SNAPSHOT" not in v]
    except Exception as exc:  # noqa: BLE001
        failures.append(f"fabric-loom: {exc}")

    if failures:
        print("\ncould not resolve:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    if not loom_versions:
        failures.append("fabric-loom: no non-snapshot release version found")
        return 1

    resolved = {
        "yarn_mappings": latest(yarn_versions),
        "loader_version": loader_stable or latest(loader_versions),
        "fabric_version": latest(api_versions),
        "loom_version": latest(loom_versions),
    }

    print("\nresolved:")
    for key, value in resolved.items():
        old = props.get(key, "<absent>")
        mark = "same" if old == value else f"was {old}"
        print(f"  {key:18} {value:28} ({mark})")

    if args.check:
        return 0

    # The deliverable pins one exact loader. Keep it: only refuse when the pin is
    # not buildable for this Minecraft version, so a silent substitution cannot
    # happen.
    pinned_loader = props.get("loader_version")
    if pinned_loader and pinned_loader != resolved["loader_version"]:
        if pinned_loader not in loader_versions:
            print(f"\nERROR: pinned loader {pinned_loader} does not exist for Minecraft {mc}.",
                  file=sys.stderr)
            print(f"       available: {', '.join(loader_versions[:5])} ...", file=sys.stderr)
            return 1
        resolved["loader_version"] = pinned_loader
        print(f"  loader_version     {pinned_loader:28} (keeping the pinned value)")

    output = []
    for line in order:
        if "=" in line and not line.strip().startswith("#"):
            key = line.split("=", 1)[0].strip()
            if key in resolved:
                line = f"{key}={resolved[key]}"
        output.append(line)
    PROPS.write_text("\n".join(output) + "\n", encoding="utf-8")
    print(f"\nwrote {PROPS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
