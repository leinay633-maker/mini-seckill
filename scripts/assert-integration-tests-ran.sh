#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="${1:-$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)}"

if [ "$#" -gt 1 ]; then
  echo "Usage: $0 [ROOT]" >&2
  exit 2
fi

python3 - "$ROOT" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1]).resolve()
source_root = root / "src" / "test" / "java"
reports_dir = root / "target" / "failsafe-reports"

if not source_root.is_dir():
    raise SystemExit(f"Integration test source directory not found: {source_root}")

sources = sorted(source_root.rglob("*IT.java"))
if not sources:
    raise SystemExit(f"No *IT.java integration test sources found under: {source_root}")

if not reports_dir.is_dir():
    raise SystemExit(f"Failsafe reports directory not found: {reports_dir}")

report_files = sorted(reports_dir.glob("TEST-*.xml"))
if not report_files:
    raise SystemExit(f"No Failsafe TEST-*.xml reports found in: {reports_dir}")


def expected_class_name(source: Path) -> str:
    text = source.read_text(encoding="utf-8")
    package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.MULTILINE)
    package = package_match.group(1) if package_match else ""
    return f"{package}.{source.stem}" if package else source.stem


reports = {}
parse_errors = []
for report_file in report_files:
    try:
        document = ET.parse(report_file)
    except (ET.ParseError, OSError) as error:
        parse_errors.append(f"{report_file.name}: {error}")
        continue

    suites = ([document.getroot()] if document.getroot().tag == "testsuite"
              else list(document.getroot().iter("testsuite")))
    for suite in suites:
        name = suite.get("name") or report_file.stem.removeprefix("TEST-")
        counters = {
            key: int(suite.get(key, "0"))
            for key in ("tests", "skipped", "errors", "failures")
        }
        current = reports.setdefault(
            name, {"tests": 0, "skipped": 0, "errors": 0, "failures": 0, "files": []}
        )
        for key, value in counters.items():
            current[key] += value
        current["files"].append(report_file.name)

if parse_errors:
    print("Invalid Failsafe XML reports:", file=sys.stderr)
    for error in parse_errors:
        print(f"  - {error}", file=sys.stderr)
    raise SystemExit(1)

failures = []
total_tests = 0
for source in sources:
    class_name = expected_class_name(source)
    report = reports.get(class_name)
    if report is None:
        # Some Failsafe/JUnit combinations expose only the simple suite name.
        report = reports.get(source.stem)
    if report is None:
        failures.append(f"{class_name}: missing TEST-*.xml report")
        continue

    tests = report["tests"]
    total_tests += tests
    if tests <= 0:
        failures.append(f"{class_name}: tests={tests}, expected > 0")
    if report["skipped"] or report["errors"] or report["failures"]:
        failures.append(
            f"{class_name}: skipped={report['skipped']} errors={report['errors']} "
            f"failures={report['failures']}"
        )

if failures:
    print("Integration test assertion failed:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    raise SystemExit(1)

print(
    f"Integration test assertion passed. "
    f"sources={len(sources)} reports={len(report_files)} tests={total_tests}"
)
PY
