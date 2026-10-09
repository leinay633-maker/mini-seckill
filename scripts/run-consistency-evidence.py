#!/usr/bin/env python3
"""Run correctness verification and archive evidence; never derive performance claims."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REQUIRED_UNIT = (
    "com.example.miniseckill.service.impl.OrderCommitBoundaryTest",
    "com.example.miniseckill.mq.SeckillConsumerTest",
    "com.example.miniseckill.job.ConsumingMessageRecoveryJobTest",
)
REQUIRED_IT = "com.example.miniseckill.integration.OrderCommitBoundaryIT"


def read_reports(directory: Path) -> dict[str, dict[str, int]]:
    reports = {}
    for path in sorted(directory.glob("TEST-*.xml")):
        document = ET.parse(path).getroot()
        suites = [document] if document.tag == "testsuite" else document.iter("testsuite")
        for suite in suites:
            name = suite.get("name", "")
            if not name or name in reports:
                raise ValueError(f"missing or duplicate suite name in {path.name}: {name}")
            counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            if min(counts.values()) < 0 or sum(counts[k] for k in ("failures", "errors", "skipped")) > counts["tests"]:
                raise ValueError(f"invalid counters in {path.name}")
            counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
            reports[name] = counts
    return reports


def summarize(root: Path) -> dict:
    """Reject missing/empty/skipped evidence. Counts are from JUnit XML, not estimates."""
    unit = read_reports(root / "target/surefire-reports")
    integration = read_reports(root / "target/failsafe-reports")
    expected_it = {REQUIRED_IT}
    for source in (root / "src/test/java").rglob("*IT.java"):
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", source.read_text(encoding="utf-8"), re.MULTILINE)
        expected_it.add(f"{package.group(1)}.{source.stem}" if package else source.stem)
    problems = []
    for label, reports, required in (("unit", unit, REQUIRED_UNIT), ("integration", integration, expected_it)):
        for name in sorted(required):
            if name not in reports:
                problems.append(f"{label}: missing suite {name}")
        for name, counts in reports.items():
            if counts["tests"] <= 0 or any(counts[k] for k in ("skipped", "failures", "errors")):
                problems.append(f"{label}: non-passing suite {name}: {counts}")
    totals = lambda reports: {key: sum(row[key] for row in reports.values())
                              for key in ("tests", "passed", "failures", "errors", "skipped")}
    return {"status": "PASS" if not problems else "FAIL", "problems": problems,
            "unit": totals(unit), "integration": totals(integration),
            "suites": {"unit": unit, "integration": integration},
            "performance": "待本机实测", "docker_fault_drill": "待本机实测"}


def capture(command: list[str], root: Path) -> str:
    try:
        result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", timeout=30, check=False)
        return f"exit={result.returncode}\n{result.stdout}"
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"unavailable: {error}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="New output directory, outside target/; refuses overwrite")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    started = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = (args.output or root / "benchmark/results" / f"consistency-{started}").resolve()
    target = (root / "target").resolve()
    if output == target or target in output.parents or output in root.parents or output == root:
        parser.error("output must be a new directory outside target/ and must not contain the repository")
    if output.exists():
        parser.error(f"refusing to overwrite existing evidence: {output}")
    output.mkdir(parents=True)
    mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
    command = [mvn or "mvn", "-B", "clean", "-Pintegration-test", "verify"]
    metadata = {
        "started_at_utc": started, "platform": platform.platform(),
        "git_head": capture(["git", "rev-parse", "HEAD"], root),
        "git_status": capture(["git", "status", "--porcelain"], root),
        "java": capture(["java", "-version"], root),
        "maven": capture([mvn or "mvn", "-version"], root),
        "docker": capture(["docker", "version", "--format", "{{.Server.Version}}"], root),
        "command": command,
    }
    exit_code = 127
    with (output / "maven.log").open("w", encoding="utf-8", newline="\n") as log:
        try:
            # No pipes: retain full output even when the build fails. No credentials/environment dump.
            result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=False)
            exit_code = result.returncode
        except OSError as error:
            log.write(f"Unable to execute Maven: {error}\n")
    metadata["maven_exit_code"] = exit_code
    for name in ("surefire-reports", "failsafe-reports", "site/jacoco"):
        source = target / name
        if source.is_dir():
            shutil.copytree(source, output / name)
    try:
        result = summarize(root)
    except (ValueError, OSError, ET.ParseError) as error:
        result = {"status": "FAIL", "problems": [f"unreadable JUnit evidence: {error}"],
                  "performance": "待本机实测", "docker_fault_drill": "待本机实测"}
    if exit_code != 0:
        result["status"] = "FAIL"
        result["problems"].append(f"Maven exited with {exit_code}")
    # Preserve the historical-report identity without changing or reinterpreting its contents.
    metadata["historical_report_sha256"] = {
        name: hashlib.sha256((root / name).read_bytes()).hexdigest()
        for name in ("REPORT.md", "REPORT-RELIABILITY.md") if (root / name).is_file()
    }
    metadata["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    for name, value in (("manifest.json", metadata), ("summary.json", result)):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksums = {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in sorted(output.rglob("*")) if path.is_file()}
    (output / "sha256.json").write_text(json.dumps(checksums, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"Evidence directory: {output}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
