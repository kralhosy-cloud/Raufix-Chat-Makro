#!/usr/bin/env python3
"""Packs a directory of .class files and resources into a mod jar.

Why this exists: a normal Fabric build uses `./gradlew build`, and Loom produces
a remapped jar. Neither Gradle nor the Fabric/Minecraft artifacts are reachable
from the environment this project was written in, so this script packs the
already-compiled classes with the JDK's own zip layout rules instead.

What it does NOT do, and does not pretend to do:
  * no remapping (Loom's `remapJar`) - the class files are whatever the compiler
    produced, so they only work with the mappings they were compiled against;
  * no dependency resolution, no Minecraft classes;
  * it never adds a `.jar` from anywhere else.

Usage:
  python3 scripts/pack-jar.py --classes DIR --resources DIR --out FILE \\
      --version 1.0.0 [--platform-override]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

FIXED_TIME = (2026, 1, 1, 0, 0, 0)   # reproducible archives


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def substitute(value: str, props: dict[str, str]) -> str:
    def repl(match: re.Match[str]) -> str:
        key = match.group(1)
        # Loom-style defaults: ${version} comes from gradle.properties' mod_version.
        if key == "version" and "mod_version" in props:
            return props["mod_version"]
        if key not in props:
            raise SystemExit(f"fabric.mod.json references ${{{key}}} but gradle.properties has no such key")
        return props[key]
    return re.sub(r"\$\{([A-Za-z0-9_.]+)\}", repl, value)


def build_manifest(json_text: str) -> str:
    return "Manifest-Version: 1.0\nCreated-By: Chat Macro Studio pack-jar.py\n\n"


def add_entry(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--classes", required=True, help="directory with .class files")
    parser.add_argument("--resources", required=True, help="directory with assets/ and fabric.mod.json")
    parser.add_argument("--out", required=True)
    parser.add_argument("--props", default="gradle.properties")
    parser.add_argument("--core", action="store_true",
                        help="pack only the platform-free domain core: no Fabric "
                             "entrypoint is advertised, because the client layer "
                             "cannot be compiled here (see KNOWN_LIMITATIONS.md)")
    args = parser.parse_args()

    classes = Path(args.classes)
    resources = Path(args.resources)
    props = read_properties(Path(args.props))

    if not classes.is_dir():
        raise SystemExit(f"no classes directory: {classes}")
    mod_json_path = resources / "fabric.mod.json"
    if not mod_json_path.is_file():
        raise SystemExit(f"missing {mod_json_path}")

    raw = mod_json_path.read_text(encoding="utf-8")
    resolved = substitute(raw, props)
    try:
        mod = json.loads(resolved)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"fabric.mod.json is not valid JSON after substitution: {exc}")
    if args.core:
        # The core has no Fabric entrypoint by construction. Rather than ship a
        # jar whose declared entrypoint is absent, mark it as a library and drop
        # the entrypoints entirely.
        mod.pop("entrypoints", None)
        mod["custom"] = dict(mod.get("custom") or {}, chatMacroStudioCore=True)
        mod["description"] = (
            "PLATFORM-FREE CORE ONLY - contains the profile/macro domain, scheduler, "
            "persistence and theming, and no Fabric entrypoint. The Minecraft client "
            "layer is not in this jar because it could not be compiled without the "
            "Fabric/Minecraft artifacts. Not installable as a mod on its own.")
        resolved = json.dumps(mod, indent=2, ensure_ascii=False) + "\n"

    # A jar is useless if its declared entrypoint is not inside it.
    missing_entrypoints = []
    for kind, entries in (mod.get("entrypoints") or {}).items():
        for entry in entries:
            cls = entry.split("::")[0] if isinstance(entry, str) else None
            if not cls:
                continue
            path = classes / (cls.replace(".", "/") + ".class")
            if not path.is_file():
                missing_entrypoints.append(f"{kind}: {cls}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    class_files = sorted(p for p in classes.rglob("*.class"))
    written: list[str] = []
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        add_entry(zf, "META-INF/MANIFEST.MF", build_manifest(resolved).encode("utf-8"))
        add_entry(zf, "fabric.mod.json", resolved.encode("utf-8"))
        written.append("fabric.mod.json")
        for path in class_files:
            name = path.relative_to(classes).as_posix()
            add_entry(zf, name, path.read_bytes())
            written.append(name)
        for path in sorted(resources.rglob("*")):
            if not path.is_file() or path.name == "fabric.mod.json":
                continue
            name = path.relative_to(resources).as_posix()
            add_entry(zf, name, path.read_bytes())
            written.append(name)

    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    size = out.stat().st_size

    report = {
        "jar": out.as_posix(),
        "bytes": size,
        "sha256": digest,
        "entries": len(written),
        "classFiles": len(class_files),
        "modId": mod.get("id"),
        "modVersion": mod.get("version"),
        "environment": mod.get("environment"),
        "entrypoints": mod.get("entrypoints"),
        "missingEntrypointClasses": missing_entrypoints,
        "remapped": False,
        "coreOnly": bool(args.core),
        "note": ("Not a Loom remapped jar: packed directly from compiled classes. "
                 "No Minecraft/Fabric classes are bundled."),
    }
    report_path = out.with_suffix(".build.json")
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(report, indent=2))
    if missing_entrypoints:
        print("ERROR: entrypoint classes missing from the jar: " + ", ".join(missing_entrypoints), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
