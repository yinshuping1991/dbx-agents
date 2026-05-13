#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path


INFRA_MODULES = {"common", "test-support"}

FORBIDDEN_PATTERNS = [
    (re.compile(r"private\s+val\s+QUERY_PREFIXES"), "forbidden local SQL prefix classifier"),
    (re.compile(r"private\s+const\s+val\s+MAX_ROWS"), "forbidden local result row cap"),
    (re.compile(r"executeUpdate\s*\(\s*trimmedSql\s*\)"), "forbidden executeUpdate(trimmedSql) in query execution"),
    (re.compile(r"truncated\s*=\s*rows\.size\s*>=\s*MAX_ROWS"), "forbidden inclusive truncation check"),
    (re.compile(r"val\s+truncated\s*=\s*rs\.next\s*\(\s*\)"), "forbidden extra ResultSet next() truncation probe"),
]


def included_agent_modules(root: Path) -> set[str]:
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    include_args = "\n".join(re.findall(r"include\((.*?)\)", settings, flags=re.S))
    return set(re.findall(r'"([^"]+)"', include_args)) - INFRA_MODULES


def validate_versions(root: Path) -> list[str]:
    included = included_agent_modules(root)
    versions = set(json.loads((root / "versions.json").read_text(encoding="utf-8")))
    return (
        [f"included module missing version: {name}" for name in sorted(included - versions)]
        + [f"versions key not included: {name}" for name in sorted(versions - included)]
    )


def validate_source_patterns(root: Path) -> list[str]:
    problems: list[str] = []
    for source in sorted(root.glob("*/src/main/**/*.kt")):
        relative = source.relative_to(root)
        text = source.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for pattern, message in FORBIDDEN_PATTERNS:
                if pattern.search(line):
                    problems.append(f"{relative}:{line_number}: {message}")
    return problems


def validate_manifest_fields(root: Path, modules: set[str]) -> list[str]:
    problems: list[str] = []
    for module in sorted(modules):
        build_file = root / module / "build.gradle.kts"
        relative = build_file.relative_to(root)
        if not build_file.exists():
            problems.append(f"{module}: missing build.gradle.kts")
            continue

        text = build_file.read_text(encoding="utf-8")
        expected_archive = f'archiveBaseName.set("dbx-agent-{module}")'
        if expected_archive not in text:
            problems.append(f"{relative}: missing {expected_archive}")
        if '"Agent-Label"' not in text:
            problems.append(f"{relative}: missing Agent-Label manifest attribute")
        if '"Main-Class"' not in text:
            problems.append(f"{relative}: missing Main-Class manifest attribute")
    return problems


def validate(root: Path) -> list[str]:
    modules = included_agent_modules(root)
    return (
        validate_versions(root)
        + validate_source_patterns(root)
        + validate_manifest_fields(root, modules)
    )


def main() -> int:
    root = Path.cwd()
    problems = validate(root)
    if problems:
        print("Agent validation failed:")
        for problem in problems:
            print(f"- {problem}")
        return 1

    print("Agent validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
