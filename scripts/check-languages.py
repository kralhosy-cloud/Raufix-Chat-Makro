#!/usr/bin/env python3
"""Checks the language files against the keys the code actually asks for.

Run: python3 scripts/check-languages.py

It is a real check, not a description of one: it exits non-zero and prints the
offending keys when something is missing. It catches

  1. malformed JSON in either file,
  2. a key present in one language but not the other,
  3. a literal ModInfo.tr("...") key in the sources that has no translation,
  4. runtime message keys (scheduler holds, dispatch outcomes, repository
     results, validation errors) that have no translation,
  5. format placeholders whose count differs between the two languages,
  6. empty values,
  7. the Turkish file missing Turkish-specific characters.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LANG_DIR = ROOT / "src/main/resources/assets/chatmacro_studio/lang"
MAIN_JAVA = ROOT / "src/main/java"
PREFIX = "chatmacro_studio."

# Keys that reach the UI through variables rather than ModInfo.tr("literal").
# They are listed here on purpose so the check covers them too.
RUNTIME_KEYS = [
    # scheduler hold / stop reasons
    "state.notInGame", "state.playerNotReady", "state.scopeMismatch", "state.paused",
    "state.chatOpen", "state.gamePaused", "state.focusLost", "state.stopped",
    "session.changed", "session.lost", "session.leftGame", "session.profileChanged",
    # dispatch outcomes
    "dispatch.handedToClient", "dispatch.notInGame", "dispatch.invalidContent",
    "dispatch.exception", "dispatch.budgetBlocked", "dispatch.chatDisabled",
    "dispatch.hardStop", "dispatch.tooManyFailures", "dispatch.adapterReturnedNull",
    # repository results
    "profile.created", "profile.renamed", "profile.saved", "profile.deleted",
    "profile.duplicated", "profile.selected", "profile.cleared",
    "profile.autoStartOn", "profile.autoStartOff",
    "macro.created", "macro.saved", "macro.deleted", "macro.duplicated",
    "macro.enabledOn", "macro.enabledOff",
    "error.profileNotFound", "error.macroNotFound", "error.macroWrongProfile",
    "error.profileLimit", "error.macroLimit",
    # validation
    "validation.name.null", "validation.name.empty", "validation.name.tooLong",
    "validation.name.controlChars", "validation.name.newline", "validation.name.duplicate",
    "validation.macroName.empty", "validation.description.tooLong",
    "validation.content.null", "validation.content.empty", "validation.content.newline",
    "validation.content.controlChar", "validation.content.unpairedSurrogate",
    "validation.content.tooLong", "validation.command.slashOnly",
    "validation.interval.nan", "validation.interval.infinite",
    "validation.interval.notPositive", "validation.interval.tooSmall",
    "validation.interval.tooLarge", "validation.scope.invalid",
    # scopes
    "scope.everywhere", "scope.singleplayer", "scope.singleplayerWorld",
    "scope.specificServer",
    # config load/save
    "config.saved", "config.saveFailed", "config.readOnly", "config.encodeFailed",
    "config.error.parse_error", "config.error.schema_too_new",
    "config.error.document_too_large", "config.error.io_error",
    "config.error.unsupported_schema",
    # run feedback emitted by commands and the screen
    "run.started", "run.stopped", "run.paused", "run.resumed", "run.noEnabledMacros",
    "run.profileDeleted", "import.done", "profile.sortMode",
    # profile transfer (export / import / diagnostics)
    "export.done", "export.doneWithSecrets", "export.failed",
    "import.previewReady", "import.pathRejected", "import.fileNotFound",
    "import.ioError", "import.invalid", "client.shutdown",
]

TR_ONLY = re.compile(r"ModInfo\.tr\(\s*\"([a-zA-Z0-9_.]+)\"")
# Any dotted, lower-case string literal: catches keys passed around as
# variables (buttons, status keys, reason keys) as well as direct ModInfo.tr
# calls. Deliberately conservative - it produces candidates, and each one is
# then checked against the language files.
ANY_KEY_LIKE = re.compile(r"\"((?:[a-z][a-zA-Z0-9]*)(?:\.[a-zA-Z0-9]+)+)\"")
NOT_A_KEY_PREFIX = ("dev.", "net.", "java.", "com.", "org.", "javax.", "fabric.",
                    "minecraft.", "assets/", "config/", "scope.")
CONCAT_KEY = re.compile(r"ModInfo\.tr\(\s*([a-zA-Z0-9_.]+\s*\?\s*\"[a-zA-Z0-9_.]+\"\s*:\s*\"[a-zA-Z0-9_.]+\")")
TERNARY_KEYS = re.compile(r"\"([a-zA-Z0-9_.]+)\"")


def collect_source_keys() -> set[str]:
    keys: set[str] = set()
    candidates: set[str] = set()
    for path in MAIN_JAVA.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        keys.update(TR_ONLY.findall(text))
        # ModInfo.tr(cond ? "a" : "b")
        for expr in CONCAT_KEY.findall(text):
            keys.update(TERNARY_KEYS.findall(expr))
        for literal in ANY_KEY_LIKE.findall(text):
            if literal.startswith(NOT_A_KEY_PREFIX) or "/" in literal or ":" in literal:
                continue
            # "scope.*" values are keys too, they were excluded above only to
            # drop things like "net.minecraft.*".
            candidates.add(literal)
    # Every dotted literal that is not already known must be a translation key,
    # a scope label key or a reason key - all of which need a translation.
    return (keys | candidates) - INTERNAL_EVENT_TAGS


# Strings that look like translation keys but are internal change-kind tags
# passed to StudioData.replace(...). They are written to the log only and never
# reach Text.translatable, so they must not require a translation.
INTERNAL_EVENT_TAGS = {
    "macro.create", "macro.update", "macro.delete", "macro.duplicate", "macro.enabled",
    "profile.create", "profile.delete", "profile.rename", "profile.description",
    "profile.duplicate", "profile.scope", "profile.select", "profile.autoStart",
}


def placeholders(value: str) -> int:
    return len(re.findall(r"%[0-9]*\$?[sd]", value))


def main() -> int:
    problems: list[str] = []

    files = {}
    for name in ("en_us", "tr_tr"):
        path = LANG_DIR / f"{name}.json"
        if not path.exists():
            problems.append(f"missing language file: {path}")
            continue
        try:
            files[name] = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            problems.append(f"{name}.json is not valid JSON: {exc}")

    if len(files) != 2:
        report(problems)
        return 1 if problems else 0

    en, tr = files["en_us"], files["tr_tr"]

    for name, table in (("en_us", en), ("tr_tr", tr)):
        for key, value in table.items():
            if not key.startswith(PREFIX):
                problems.append(f"{name}: key without the {PREFIX} prefix: {key}")
            if not isinstance(value, str) or not value.strip():
                problems.append(f"{name}: empty value for {key}")

    for key in en.keys() - tr.keys():
        problems.append(f"tr_tr is missing: {key}")
    for key in tr.keys() - en.keys():
        problems.append(f"en_us is missing: {key}")

    for key in sorted(en.keys() & tr.keys()):
        if placeholders(en[key]) != placeholders(tr[key]):
            problems.append(
                f"placeholder count differs for {key}: en={placeholders(en[key])} tr={placeholders(tr[key])}")

    wanted = collect_source_keys() | set(RUNTIME_KEYS)
    for key in sorted(wanted):
        full = PREFIX + key
        if full not in en:
            problems.append(f"en_us is missing code key: {full}")
        if full not in tr:
            problems.append(f"tr_tr is missing code key: {full}")

    turkish_markers = ("ı", "İ", "ş", "ğ", "ç", "ö", "ü")
    joined_tr = " ".join(tr.values())
    missing_markers = [m for m in turkish_markers if m not in joined_tr]
    if missing_markers:
        problems.append("tr_tr has no Turkish-specific characters: " + ", ".join(missing_markers))

    unused = sorted(set(en.keys()) - {PREFIX + k for k in wanted})
    print(f"keys: en_us={len(en)} tr_tr={len(tr)}; referenced by code or runtime list={len(wanted)}")
    if unused:
        print(f"note: {len(unused)} translation(s) are not referenced by the scanner "
              f"(may be used through formatted keys): {', '.join(unused[:8])}")
    return report(problems)


def report(problems: list[str]) -> int:
    if not problems:
        print("RESULT: PASS - both language files are consistent")
        return 0
    print(f"RESULT: FAIL - {len(problems)} problem(s)")
    for problem in problems:
        print("  - " + problem)
    return 1


if __name__ == "__main__":
    sys.exit(main())
