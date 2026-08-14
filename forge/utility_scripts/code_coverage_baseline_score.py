# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

"""
Neutral JaCoCo scorer for the unguided code coverage baseline
(§WF-code-coverage-baseline.3.2).

The baseline arm has no API inventory to correlate against, so it cannot use
`code_coverage_validate.py`. What it needs instead is the one number both arms
can be compared on: how many of the methods the *library itself* declares are
covered. That denominator is obtained by intersecting the JaCoCo report with the
classes the resolved main jars declare — the same exclusion the guided arm
applies through its bytecode method list, reached here without a call graph.

Raw `jacocoMethods` is not that number and must never be reported as coverage: a
JaCoCo report covers every instrumented class on the test runtime classpath,
which on kafka-streams inflated the apparent universe fivefold
(§WF-code-coverage-improvement.3.2).

Usage:
  python3 utility_scripts/code_coverage_baseline_score.py \
    --repo-path <worktree> --coordinate group:artifact:version \
    --library-jar <main.jar> [--library-jar ...] \
    --iteration 0 --output-dir runtime/code-coverage-baseline/score \
    [--jacoco-dir runtime/code-coverage-baseline/report] \
    [--baseline-report <baseline-report-0.json>] [--skip-gradle]
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import sys
import zipfile

from utility_scripts.code_coverage_jacoco import (
    JacocoMethodCoverage,
    JacocoReportError,
    load_jacoco_method_coverage,
)
from utility_scripts.code_coverage_validate import find_jacoco_reports
from utility_scripts.gradle_test_runner import run_gradle_test_command

GRADLE_TASKS: tuple[str, ...] = ("compileTestJava", "codeCoverageTest", "jacocoCodeCoverageReport")


class BaselineScoreError(RuntimeError):
    """Raised when the baseline score cannot be computed from the given evidence."""


def select_main_jars(jar_paths: list[str], artifact: str, version: str) -> tuple[list[str], str]:
    """Keep the classifier-free main artifact, and report how it was selected.

    A `test`-classifier jar of the library's own unit tests resolves onto the
    same test runtime classpath, so selecting by exact filename is what keeps
    this workflow pointed at the library (§WF-code-coverage-baseline.3.1).
    """
    if not jar_paths:
        raise BaselineScoreError("No library jars were provided.")
    exact_name: str = f"{artifact}-{version}.jar"
    exact: list[str] = [path for path in jar_paths if os.path.basename(path) == exact_name]
    if exact:
        return sorted(exact), "exact-name"
    # Relocated or substituted modules publish under a different artifact name,
    # so exact matching can legitimately find nothing. Fall back to every jar
    # that carries no classifier suffix, and say so in the report.
    prefix: str = f"{artifact}-{version}-"
    unclassified: list[str] = [
        path for path in jar_paths if not os.path.basename(path).startswith(prefix)
    ]
    if not unclassified:
        raise BaselineScoreError(
            f"Every provided jar looks like a classifier artifact of '{artifact}:{version}': "
            f"{', '.join(sorted(os.path.basename(path) for path in jar_paths))}."
        )
    return sorted(unclassified), "classifier-excluded"


def library_class_names(jar_paths: list[str]) -> set[str]:
    """Return every binary class name the given jars declare."""
    names: set[str] = set()
    for jar_path in jar_paths:
        try:
            with zipfile.ZipFile(jar_path) as jar:
                for entry in jar.namelist():
                    if entry.endswith(".class"):
                        names.add(entry[: -len(".class")].replace("/", "."))
        except (OSError, zipfile.BadZipFile) as error:
            raise BaselineScoreError(f"Cannot read library jar '{jar_path}': {error}") from error
    if not names:
        raise BaselineScoreError(
            f"Library jars declare no classes: {', '.join(sorted(jar_paths))}."
        )
    return names


def _percent(covered: int, total: int) -> float:
    return round(100.0 * covered / total, 2) if total else 0.0


def score_methods(
        methods: dict[str, JacocoMethodCoverage],
        library_classes: set[str],
) -> dict:
    """Split JaCoCo method records into the library universe and score it."""
    library: list[JacocoMethodCoverage] = [
        coverage for coverage in methods.values()
        if coverage.method_ref.owner in library_classes
    ]
    covered: int = sum(1 for coverage in library if coverage.covered)
    packages: dict[str, dict[str, int]] = {}
    for coverage in library:
        owner: str = coverage.method_ref.owner
        package: str = owner.rsplit(".", 1)[0] if "." in owner else ""
        entry: dict[str, int] = packages.setdefault(package, {"methods": 0, "covered": 0})
        entry["methods"] += 1
        entry["covered"] += 1 if coverage.covered else 0

    return {
        "summary": {
            "jacocoMethods": len(methods),
            "libraryMethods": len(library),
            "nonLibraryMethodsExcluded": len(methods) - len(library),
            "covered": covered,
            "uncovered": len(library) - covered,
            "coveredPercent": _percent(covered, len(library)),
        },
        # Sorted by remaining uncovered mass: where coverage is still available.
        "packages": sorted(
            (
                {
                    "package": package,
                    "methods": entry["methods"],
                    "covered": entry["covered"],
                    "uncovered": entry["methods"] - entry["covered"],
                    "coveredPercent": _percent(entry["covered"], entry["methods"]),
                }
                for package, entry in packages.items()
            ),
            key=lambda entry: (-entry["uncovered"], entry["package"]),
        ),
    }


def _gradle_steps(repo_path: str, coordinate: str) -> list[dict]:
    steps: list[dict] = []
    for task in GRADLE_TASKS:
        command: str = f"./gradlew {task} {shlex.quote(f'-Pcoordinates={coordinate}')} --stacktrace"
        output: str = run_gradle_test_command(command, working_dir=repo_path, library=coordinate)
        succeeded: bool = "BUILD SUCCESSFUL" in output
        steps.append({
            "task": task,
            "command": command,
            "succeeded": succeeded,
            "tail": output[-2000:] if not succeeded else "",
        })
        if not succeeded:
            raise JacocoReportError(
                f"Gradle task '{task}' failed; refusing to score a stale JaCoCo report. "
                f"Command: {command}"
            )
    return steps


def _delta(current: dict, baseline_path: str) -> dict:
    """Return the covered/percent movement against an earlier report."""
    try:
        with open(baseline_path, "r", encoding="utf-8") as baseline_file:
            baseline: dict = json.load(baseline_file)["summary"]
    except (OSError, json.JSONDecodeError, KeyError) as error:
        raise BaselineScoreError(
            f"Cannot read baseline report '{baseline_path}': {error}"
        ) from error
    return {
        "baselineCovered": baseline["covered"],
        "baselineCoveredPercent": baseline["coveredPercent"],
        "coveredDelta": current["covered"] - baseline["covered"],
        "coveredPercentDelta": round(current["coveredPercent"] - baseline["coveredPercent"], 2),
        "libraryMethodsDelta": current["libraryMethods"] - baseline["libraryMethods"],
    }


def _write_markdown(report: dict, path: str) -> None:
    summary: dict = report["summary"]
    lines: list[str] = [
        f"# Baseline coverage score - iteration {report['iteration']}",
        "",
        f"- Coordinate: `{report['coordinate']}`",
        f"- Library methods: {summary['libraryMethods']} "
        f"({summary['covered']} covered, {summary['uncovered']} uncovered, "
        f"{summary['coveredPercent']}%)",
        f"- JaCoCo methods: {summary['jacocoMethods']} "
        f"({summary['nonLibraryMethodsExcluded']} excluded as non-library)",
        f"- Main jars ({report['jarSelection']}): "
        f"{', '.join(os.path.basename(path) for path in report['libraryJars'])}",
    ]
    delta: dict | None = report.get("delta")
    if delta:
        lines.append(
            f"- Movement: {delta['coveredDelta']:+d} methods, "
            f"{delta['coveredPercentDelta']:+.2f} points from "
            f"{delta['baselineCoveredPercent']}%"
        )
    lines += ["", "## Packages by remaining uncovered methods", ""]
    lines += ["| package | methods | covered | uncovered | % |", "| --- | ---: | ---: | ---: | ---: |"]
    lines += [
        f"| `{entry['package']}` | {entry['methods']} | {entry['covered']} | "
        f"{entry['uncovered']} | {entry['coveredPercent']} |"
        for entry in report["packages"][:40]
    ]
    with open(path, "w", encoding="utf-8") as markdown_file:
        markdown_file.write("\n".join(lines) + "\n")


def score(
        repo_path: str,
        coordinate: str,
        library_jars: list[str],
        iteration: int,
        output_dir: str,
        jacoco_dir: str | None,
        baseline_report: str | None,
        skip_gradle: bool,
) -> dict:
    """Run the coverage tasks, score the report, and write the score artifacts."""
    coordinate_parts: list[str] = coordinate.split(":")
    if len(coordinate_parts) != 3 or any(not part for part in coordinate_parts):
        raise BaselineScoreError(
            f"Coordinate must use non-empty group:artifact:version form; got '{coordinate}'."
        )
    group: str
    artifact: str
    version: str
    group, artifact, version = coordinate_parts

    main_jars: list[str]
    jar_selection: str
    main_jars, jar_selection = select_main_jars(library_jars, artifact, version)
    library_classes: set[str] = library_class_names(main_jars)

    gradle_steps: list[dict] = [] if skip_gradle else _gradle_steps(repo_path, coordinate)
    jacoco_xml_paths: list[str] = find_jacoco_reports(repo_path, group, artifact, version)
    if not jacoco_xml_paths:
        raise JacocoReportError(
            f"No JaCoCo report was produced for '{coordinate}' under '{repo_path}'."
        )
    methods: dict[str, JacocoMethodCoverage] = load_jacoco_method_coverage(jacoco_xml_paths)

    report: dict = {
        "coordinate": coordinate,
        "iteration": iteration,
        "libraryJars": [os.path.abspath(path) for path in main_jars],
        "jarSelection": jar_selection,
        "libraryClasses": len(library_classes),
        "jacocoXmlPaths": [os.path.abspath(path) for path in jacoco_xml_paths],
        "gradleSteps": gradle_steps,
        **score_methods(methods, library_classes),
    }
    if baseline_report:
        report["delta"] = _delta(report["summary"], baseline_report)

    os.makedirs(output_dir, exist_ok=True)
    json_path: str = os.path.join(output_dir, f"baseline-report-{iteration}.json")
    with open(json_path, "w", encoding="utf-8") as json_file:
        json.dump(report, json_file, indent=2, sort_keys=True)
        json_file.write("\n")
    _write_markdown(report, os.path.join(output_dir, f"baseline-report-{iteration}.md"))

    # The agent-visible half: only the raw report, at a fixed path and an
    # iteration-stamped copy. The score above is deliberately not placed here
    # (§WF-code-coverage-baseline.3.2).
    if jacoco_dir:
        os.makedirs(jacoco_dir, exist_ok=True)
        shutil.copy(jacoco_xml_paths[0], os.path.join(jacoco_dir, f"jacoco-{iteration}.xml"))
        shutil.copy(jacoco_xml_paths[0], os.path.join(jacoco_dir, "jacoco.xml"))
    return report


def build_parser() -> argparse.ArgumentParser:
    """Return the command line parser for the baseline scorer."""
    parser = argparse.ArgumentParser(description="Score library coverage from a JaCoCo report.")
    parser.add_argument("--repo-path", required=True, help="Issue worktree / repo root.")
    parser.add_argument("--coordinate", required=True, help="group:artifact:version.")
    parser.add_argument(
        "--library-jar", action="append", required=True,
        help="Resolved library jar path (repeatable); classifier jars are dropped.",
    )
    parser.add_argument("--iteration", type=int, default=0, help="Measurement iteration number.")
    parser.add_argument("--output-dir", required=True, help="Directory for score artifacts.")
    parser.add_argument(
        "--jacoco-dir", default=None,
        help="Directory receiving the agent-visible JaCoCo XML copies.",
    )
    parser.add_argument(
        "--baseline-report", default=None,
        help="Earlier baseline-report JSON to compute movement against.",
    )
    parser.add_argument(
        "--skip-gradle", action="store_true",
        help="Score the existing JaCoCo report without re-running the Gradle tasks.",
    )
    return parser


def main() -> int:
    """Score one iteration and print its headline numbers."""
    args = build_parser().parse_args()
    try:
        report: dict = score(
            repo_path=args.repo_path,
            coordinate=args.coordinate,
            library_jars=args.library_jar,
            iteration=args.iteration,
            output_dir=args.output_dir,
            jacoco_dir=args.jacoco_dir,
            baseline_report=args.baseline_report,
            skip_gradle=args.skip_gradle,
        )
    except (BaselineScoreError, JacocoReportError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    summary: dict = report["summary"]
    print(
        f"[baseline-score] iteration {report['iteration']}: "
        f"{summary['covered']}/{summary['libraryMethods']} library methods "
        f"({summary['coveredPercent']}%), {summary['uncovered']} uncovered"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
