# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

import json
import os
import tempfile
import unittest
import zipfile

from utility_scripts.code_coverage_baseline_score import (
    BaselineScoreError,
    library_class_names,
    score_methods,
    select_main_jars,
)
from utility_scripts.code_coverage_jacoco import load_jacoco_method_coverage

FIXTURE_JACOCO: str = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "fixtures", "code_coverage", "validator_exact_jacoco.xml",
)


def write_jar(directory: str, name: str, classes: tuple[str, ...]) -> str:
    """Write a jar carrying the given binary class names and return its path."""
    jar_path: str = os.path.join(directory, name)
    with zipfile.ZipFile(jar_path, "w") as jar:
        for binary_name in classes:
            jar.writestr(binary_name.replace(".", "/") + ".class", b"\xca\xfe\xba\xbe")
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    return jar_path


class SelectMainJarsTests(unittest.TestCase):

    def test_classifier_artifact_is_excluded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            main: str = write_jar(directory, "greeter-1.0.jar", ("com.example.Greeter",))
            tests: str = write_jar(directory, "greeter-1.0-test.jar", ("com.example.GreeterTest",))

            selected: list[str]
            selection: str
            selected, selection = select_main_jars([tests, main], "greeter", "1.0")

            self.assertEqual([main], selected)
            self.assertEqual("exact-name", selection)

    def test_relocated_module_falls_back_to_unclassified_jars(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            relocated: str = write_jar(directory, "greeter-core-1.0.jar", ("com.example.Greeter",))

            selected: list[str]
            selection: str
            selected, selection = select_main_jars([relocated], "greeter", "1.0")

            self.assertEqual([relocated], selected)
            self.assertEqual("classifier-excluded", selection)

    def test_only_classifier_jars_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tests: str = write_jar(directory, "greeter-1.0-test.jar", ("com.example.GreeterTest",))

            with self.assertRaises(BaselineScoreError):
                select_main_jars([tests], "greeter", "1.0")

    def test_no_jars_is_an_error(self) -> None:
        with self.assertRaises(BaselineScoreError):
            select_main_jars([], "greeter", "1.0")


class LibraryClassNamesTests(unittest.TestCase):

    def test_class_entries_become_binary_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            jar: str = write_jar(directory, "greeter-1.0.jar", ("com.example.Greeter", "com.example.Overloaded"))

            self.assertEqual(
                {"com.example.Greeter", "com.example.Overloaded"},
                library_class_names([jar]),
            )

    def test_jar_without_classes_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            empty: str = write_jar(directory, "greeter-1.0.jar", ())

            with self.assertRaises(BaselineScoreError):
                library_class_names([empty])


class ScoreMethodsTests(unittest.TestCase):

    def setUp(self) -> None:
        self.methods = load_jacoco_method_coverage([FIXTURE_JACOCO])

    def test_denominator_is_restricted_to_library_declared_classes(self) -> None:
        # `Greeter` is absent from the jar, so its one method is reported by
        # JaCoCo but excluded from the universe (§WF-code-coverage-baseline.3.2).
        report: dict = score_methods(self.methods, {"com.example.Overloaded"})

        summary: dict = report["summary"]
        self.assertEqual(4, summary["jacocoMethods"])
        self.assertEqual(3, summary["libraryMethods"])
        self.assertEqual(1, summary["nonLibraryMethodsExcluded"])
        self.assertEqual(1, summary["covered"])
        self.assertEqual(2, summary["uncovered"])
        self.assertEqual(33.33, summary["coveredPercent"])

    def test_whole_report_counts_when_every_class_is_library_owned(self) -> None:
        report: dict = score_methods(
            self.methods, {"com.example.Greeter", "com.example.Overloaded"}
        )

        summary: dict = report["summary"]
        self.assertEqual(4, summary["libraryMethods"])
        self.assertEqual(0, summary["nonLibraryMethodsExcluded"])
        self.assertEqual(25.0, summary["coveredPercent"])

    def test_packages_are_ordered_by_remaining_uncovered_methods(self) -> None:
        report: dict = score_methods(
            self.methods, {"com.example.Greeter", "com.example.Overloaded"}
        )

        packages: list[dict] = report["packages"]
        self.assertEqual(["com.example"], [entry["package"] for entry in packages])
        self.assertEqual(3, packages[0]["uncovered"])

    def test_empty_library_universe_scores_zero_without_dividing(self) -> None:
        report: dict = score_methods(self.methods, {"com.other.Absent"})

        self.assertEqual(0, report["summary"]["libraryMethods"])
        self.assertEqual(0.0, report["summary"]["coveredPercent"])
        self.assertEqual([], report["packages"])


class DeltaTests(unittest.TestCase):

    def test_delta_reports_movement_against_an_earlier_report(self) -> None:
        # pylint: disable=protected-access
        from utility_scripts.code_coverage_baseline_score import _delta

        with tempfile.TemporaryDirectory() as directory:
            baseline_path: str = os.path.join(directory, "baseline-report-0.json")
            with open(baseline_path, "w", encoding="utf-8") as baseline_file:
                json.dump(
                    {"summary": {"covered": 100, "coveredPercent": 10.0, "libraryMethods": 1000}},
                    baseline_file,
                )

            delta: dict = _delta(
                {"covered": 460, "coveredPercent": 46.0, "libraryMethods": 1000},
                baseline_path,
            )

            self.assertEqual(360, delta["coveredDelta"])
            self.assertEqual(36.0, delta["coveredPercentDelta"])
            self.assertEqual(0, delta["libraryMethodsDelta"])

    def test_unreadable_baseline_is_an_error(self) -> None:
        # pylint: disable=protected-access
        from utility_scripts.code_coverage_baseline_score import _delta

        with self.assertRaises(BaselineScoreError):
            _delta({"covered": 1, "coveredPercent": 1.0, "libraryMethods": 2}, "/nonexistent.json")


if __name__ == "__main__":
    unittest.main()
