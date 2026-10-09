"""Synthetic XML tests for the checker itself, never project experiment results."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("run-consistency-evidence.py"))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class EvidenceCheckerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in evidence.REQUIRED_UNIT:
            self.report("surefire", name)
        self.report("failsafe", evidence.REQUIRED_IT)

    def report(self, kind, name, tests=1, skipped=0, failures=0, errors=0):
        directory = self.root / "target" / f"{kind}-reports"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"TEST-{name}.xml").write_text(
            f'<testsuite name="{name}" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="{errors}"/>',
            encoding="utf-8")

    def test_valid_reports_pass(self):
        result = evidence.summarize(self.root)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(3, result["unit"]["passed"])
        self.assertEqual(1, result["integration"]["passed"])
        self.assertEqual("待本机实测", result["performance"])

    def test_missing_integration_report_fails(self):
        next((self.root / "target/failsafe-reports").glob("*.xml")).unlink()
        self.assertEqual("FAIL", evidence.summarize(self.root)["status"])

    def test_skipped_container_test_fails(self):
        self.report("failsafe", evidence.REQUIRED_IT, skipped=1)
        self.assertEqual("FAIL", evidence.summarize(self.root)["status"])

    def test_zero_tests_fails(self):
        self.report("failsafe", evidence.REQUIRED_IT, tests=0)
        self.assertEqual("FAIL", evidence.summarize(self.root)["status"])

    def test_failure_and_error_counts_fail(self):
        for field in ("failures", "errors"):
            with self.subTest(field=field):
                self.report("failsafe", evidence.REQUIRED_IT, **{field: 1})
                self.assertEqual("FAIL", evidence.summarize(self.root)["status"])

    def test_new_integration_source_requires_report(self):
        source = self.root / "src/test/java/example/NewIT.java"
        source.parent.mkdir(parents=True)
        source.write_text("package example;\nclass NewIT {}", encoding="utf-8")
        self.assertEqual("FAIL", evidence.summarize(self.root)["status"])

    def test_invalid_counters_are_rejected(self):
        self.report("failsafe", evidence.REQUIRED_IT, tests=1, skipped=2)
        with self.assertRaises(ValueError):
            evidence.summarize(self.root)

    def test_missing_unit_suite_fails(self):
        next((self.root / "target/surefire-reports").glob("*.xml")).unlink()
        self.assertEqual("FAIL", evidence.summarize(self.root)["status"])


if __name__ == "__main__":
    unittest.main()
