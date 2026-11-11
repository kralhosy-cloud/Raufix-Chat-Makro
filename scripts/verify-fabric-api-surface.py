#!/usr/bin/env python3
"""Checks the Fabric API surface the mod uses against the published API index.

Fabric publishes a machine-readable class index for every API module on its
Maven (`<artifact>.jar` + `-sources`), but that host is unreachable here. The
next best thing that IS reachable: the `fabric-api` artifacts embed a
`fabric.mod.json`, and the API's own class names are stable and documented.

This script does not guess: it takes the list of Fabric types the code imports
and the list of types the Fabric API is known to export for the target line,
and fails when the code names something the API does not have. The known list is
maintained in `tools/fabric-api-surface.json` with the source of each entry.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/java"
SURFACE = ROOT / "tools/fabric-api-surface.json"


def imported_types() -> set[str]:
    types: set[str] = set()
    for path in SRC.rglob("*.java"):
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.match(r"import\s+(net\.fabricmc\.[\w.]+);", line.strip())
            if match:
                types.add(match.group(1))
    return types


def main() -> int:
    if not SURFACE.is_file():
        print(f"missing {SURFACE}", file=sys.stderr)
        return 2
    surface = json.loads(SURFACE.read_text(encoding="utf-8"))
    known = surface["types"]
    used = sorted(imported_types())
    unknown = [t for t in used if t not in known]
    unused = [t for t in known if t not in used]

    print(f"Fabric types imported by the mod: {len(used)}")
    for t in used:
        module = known.get(t, {}).get("module") if isinstance(known.get(t), dict) else None
        print(f"  {'OK ' if t in known else 'MISS'} {t}{f'  [{module}]' if module else ''}")
    if unknown:
        print("\nERROR: the code imports Fabric types this index does not list:", file=sys.stderr)
        for t in unknown:
            print(f"  {t}", file=sys.stderr)
        print("Either the index is stale or the import is wrong. Fix one of them.", file=sys.stderr)
        return 1
    if unused:
        print(f"\nnote: {len(unused)} indexed type(s) are no longer imported: {', '.join(unused)}")
    print(f"\nRESULT: PASS - all {len(used)} Fabric imports are in the {surface['target']} surface")
    return 0


if __name__ == "__main__":
    sys.exit(main())
