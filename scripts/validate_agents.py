#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path


INFRA_MODULES = {"common", "test-support"}
SOURCE_GLOBS = ("*/src/main/**/*.java",)
KOTLIN_FILE_SUFFIXES = (".kt", ".kts")
KOTLIN_SCAN_EXCLUDED_PARTS = {".git", ".gradle", "build"}

FORBIDDEN_PATTERNS = [
    (re.compile(r"private\s+val\s+QUERY_PREFIXES"), "forbidden local SQL prefix classifier"),
    (re.compile(r"private\s+const\s+val\s+MAX_ROWS"), "forbidden local result row cap"),
    (re.compile(r"executeUpdate\s*\(\s*trimmedSql\s*\)"), "forbidden executeUpdate(trimmedSql) in query execution"),
    (re.compile(r"truncated\s*=\s*rows\.size\s*>=\s*MAX_ROWS"), "forbidden inclusive truncation check"),
    (re.compile(r"val\s+truncated\s*=\s*rs\.next\s*\(\s*\)"), "forbidden extra ResultSet next() truncation probe"),
]


def included_agent_modules(root: Path) -> set[str]:
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    include_args = "\n".join(
        parenthesized or bare
        for parenthesized, bare in re.findall(
            r"\binclude\b\s*(?:\((.*?)\)|([^\n]+))", settings, flags=re.S
        )
    )
    return set(re.findall(r"""['"]([^'"]+)['"]""", include_args)) - INFRA_MODULES


def validate_versions(root: Path) -> list[str]:
    included = included_agent_modules(root)
    versions = set(json.loads((root / "versions.json").read_text(encoding="utf-8")))
    return (
        [f"included module missing version: {name}" for name in sorted(included - versions)]
        + [f"versions key not included: {name}" for name in sorted(versions - included)]
    )


def validate_source_patterns(root: Path) -> list[str]:
    problems: list[str] = []
    sources = sorted(source for glob in SOURCE_GLOBS for source in root.glob(glob))
    for source in sources:
        relative = source.relative_to(root)
        text = source.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for pattern, message in FORBIDDEN_PATTERNS:
                if pattern.search(line):
                    problems.append(f"{relative}:{line_number}: {message}")
    return problems


def validate_no_kotlin_residue(root: Path) -> list[str]:
    problems: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in KOTLIN_SCAN_EXCLUDED_PARTS for part in relative.parts):
            continue
        if path.suffix in KOTLIN_FILE_SUFFIXES:
            problems.append(f"{relative}: forbidden Kotlin file")
    return problems


def validate_manifest_fields(root: Path, modules: set[str]) -> list[str]:
    problems: list[str] = []
    root_build_text = (root / "build.gradle").read_text(encoding="utf-8") if (root / "build.gradle").exists() else ""
    archive_convention = re.search(
        r"archiveBaseName\s*=\s*['\"]dbx-agent-\$\{project\.name\}['\"]",
        root_build_text,
    )
    for module in sorted(modules):
        build_file = root / module / "build.gradle"
        relative = build_file.relative_to(root)
        if not build_file.exists():
            problems.append(f"{module}: missing build.gradle")
            continue

        text = build_file.read_text(encoding="utf-8")
        expected_archive = f"archiveBaseName = 'dbx-agent-{module}'"
        archive_pattern = re.compile(
            rf"archiveBaseName\s*=\s*['\"]dbx-agent-{re.escape(module)}['\"]"
        )
        if not archive_convention and not archive_pattern.search(text):
            problems.append(f"{relative}: missing {expected_archive}")
        attrs = manifest_attributes(text)
        if "Agent-Label" not in attrs:
            problems.append(f"{relative}: missing Agent-Label manifest attribute")
        main_class = attrs.get("Main-Class")
        if main_class is None:
            problems.append(f"{relative}: missing Main-Class manifest attribute")
        else:
            main_source = root / module / "src/main/java" / Path(*main_class.split(".")).with_suffix(".java")
            if not main_source.exists():
                problems.append(f"{relative}: Main-Class source not found: {main_source.relative_to(root)}")
    return problems


def manifest_attributes(build_text: str) -> dict[str, str]:
    attrs: dict[str, str] = {}
    for key, value in re.findall(
        r"['\"]([^'\"]+)['\"]\s*:\s*['\"]([^'\"]+)['\"]",
        build_text,
    ):
        attrs[key] = value
    return attrs


def validate(root: Path) -> list[str]:
    modules = included_agent_modules(root)
    return (
        validate_versions(root)
        + validate_source_patterns(root)
        + validate_manifest_fields(root, modules)
        + validate_no_kotlin_residue(root)
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
