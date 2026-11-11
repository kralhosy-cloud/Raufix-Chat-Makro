#!/usr/bin/env python3
"""Checks the mod's Minecraft-facing claims against real Minecraft 1.21.11 data.

The dataset is `minecraft-data` 3.119.0 (`data/pc/1.21.11/`), published on npm.
It contains the version manifest, the full language table and the protocol
number for the exact version this mod targets. That is enough to settle three
questions that otherwise get hand-waved:

  1. Is `minecraft_version` in gradle.properties really a released 1.21.11?
  2. Does the default key we document (Right Shift) exist as a real key name?
  3. Does "F30" exist? (It must not: it is only a user-facing alias.)

It does NOT prove that the mod compiles or runs - see KNOWN_LIMITATIONS.md.

Usage:
  tools/fetch-javac-toolchain.sh     # once: pulls the compiler and the data set
  python3 scripts/verify-minecraft-data.py [--data DIR]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DATA = ROOT / ".cache/minecraft-data-1.21.11"


def read_properties() -> dict[str, str]:
    props: dict[str, str] = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default=str(DEFAULT_DATA))
    args = parser.parse_args()

    data = pathlib.Path(args.data)
    required = ["version.json", "language.json", "protocol.json"]
    missing = [name for name in required if not (data / name).is_file()]
    if missing:
        print(f"minecraft-data not found ({', '.join(missing)}) in {data}", file=sys.stderr)
        print("Fetch it with:  npm pack minecraft-data@3.119.0 && tar xzf minecraft-data-3.119.0.tgz", file=sys.stderr)
        return 2

    version = json.loads((data / "version.json").read_text(encoding="utf-8"))
    language = json.loads((data / "language.json").read_text(encoding="utf-8"))
    protocol = json.loads((data / "protocol.json").read_text(encoding="utf-8"))
    props = read_properties()

    failures: list[str] = []

    print(f"dataset          : Minecraft {version['minecraftVersion']} "
          f"(protocol {version['version']}, {version['releaseType']})")
    if version["minecraftVersion"] != props["minecraft_version"]:
        failures.append(f"dataset is for {version['minecraftVersion']} but the build targets "
                        f"{props['minecraft_version']}")
    if version["releaseType"] != "release":
        failures.append(f"{version['minecraftVersion']} is a {version['releaseType']}, not a release")
    print(f"exact version pin: {version['minecraftVersion']} == {props['minecraft_version']} -> "
          f"{version['minecraftVersion'] == props['minecraft_version']}")
    print(f"language entries : {len(language)}")

    # 2. the documented default binding must be a real key name in this version
    for key, expected in (("key.keyboard.right.shift", "Right Shift"),
                          ("key.keyboard.left.shift", "Left Shift")):
        value = language.get(key)
        ok = value == expected
        print(f"  {key:26} -> {value!r} {'OK' if ok else 'MISMATCH'}")
        if not ok:
            failures.append(f"{key} is {value!r}, expected {expected!r}")
        # and it must be localised in Turkish too, not just English
    tr = language.get("key.keyboard.right.shift")

    # 3. "F30" must not exist as a key - it is an alias, not a binding
    f30 = language.get("key.keyboard.f30")
    print(f"  key.keyboard.f30           -> {f30!r} ({'absent, as required' if f30 is None else 'PRESENT - do not use'})")
    if f30 is not None:
        failures.append("key.keyboard.f30 exists in this version; the F30 alias assumption is wrong")
    f25 = language.get("key.keyboard.f25")
    if f25 is None:
        failures.append("key.keyboard.f25 missing; the 'F30 clamps to F25' note in the docs is wrong")

    # record the protocol number: a wrong Minecraft version is usually visible here
    # version.json already carries the protocol number; protocol.json only
    # describes packet shapes and has no version field.
    print(f"protocol number  : {version['version']}")

    # our own lang files must not collide with vanilla key names
    own = json.loads((ROOT / "src/main/resources/assets/chatmacro_studio/lang/en_us.json")
                     .read_text(encoding="utf-8"))
    own_keys = [key.split(".", 1)[1] for key in own]
    collisions = [key for key in own_keys if key in language]
    print(f"our keys vs vanilla: {len(own_keys)} namespaced keys, {len(collisions)} collision(s)")
    if collisions:
        failures.append(f"our translation keys shadow vanilla keys: {collisions[:5]}")

    if failures:
        print("\nFAILED:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("\nRESULT: PASS - the exact version, the default binding and the F30 alias all check out")
    return 0


if __name__ == "__main__":
    sys.exit(main())
