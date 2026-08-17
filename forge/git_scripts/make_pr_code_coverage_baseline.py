# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

"""Publish the unguided code coverage baseline arm (§WF-code-coverage-baseline).

The guided publisher builds its body from per-phase API/deep metrics this arm
never produces (§WF-code-coverage-baseline.2), so the control arm gets its own
helper rather than a parameter on that one. Everything the body states is read
from `final-score.json` (§WF-code-coverage-baseline.3.2) or from Rhei
accounting; nothing is hand-written, because a section an agent types is a
section the next run silently drops.

The publication target is a personal fork, so the repository, remote, and base
branch below are constants an experiment owner edits in one line each.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from typing import Any

from git_scripts.common_git import (
    build_ai_branch_name,
    delete_remote_branch_if_exists,
    gh,
    get_origin_owner,
    run_git_transport,
    stage_and_commit,
)
from git_scripts.pr_publication import parse_pr_number
from utility_scripts.metadata_index import resolve_test_dir

#: The baseline runs against a personal fork; no reviewers, no labels exist there.
REPO: str = "kimeta/graalvm-reachability-metadata"
PUSH_REMOTE: str = "kimeta"
#: The fork's `master` deliberately carries this workflow's branch rather than
#: mirroring upstream. The run branch is created from the HEAD of the source
#: checkout, so basing on an upstream mirror would put all of the workflow's own
#: infrastructure commits into the pull request diff; against this base the diff
#: is exactly the tests one run generated, which is what the arm is read for.
#: Re-syncing the fork's master to upstream breaks that and needs --base-branch.
BASE_BRANCH: str = "master"

FINAL_SCORE_FILENAME: str = "final-score.json"
MAX_COMMIT_SUBJECT_LENGTH: int = 60
#: Enough packages to show where the remaining mass sits, few enough to read.
TOP_PACKAGE_COUNT: int = 15


class BaselinePublicationError(RuntimeError):
    """Raised when the recorded evidence cannot support a PR body."""


def _signed(value: int | float) -> str:
    return f"{'+' if value > 0 else ''}{value}"


def _percent(covered: int, total: int) -> float:
    return round(100.0 * covered / total, 2) if total else 0.0


def load_final_score(finalization_dir: str) -> dict[str, Any]:
    """Load the scorer's final report, or fail with the path that is missing."""
    score_path: str = os.path.join(finalization_dir, FINAL_SCORE_FILENAME)
    if not os.path.isfile(score_path):
        raise BaselinePublicationError(
            f"Baseline final score not found: {score_path}. The finalize state "
            f"copies baseline-report-<iteration>.json to {FINAL_SCORE_FILENAME}."
        )
    try:
        with open(score_path, encoding="utf-8") as score_file:
            score: Any = json.load(score_file)
    except (OSError, json.JSONDecodeError) as error:
        raise BaselinePublicationError(
            f"Cannot read baseline final score '{score_path}': {error}"
        ) from error
    if not isinstance(score, dict) or not isinstance(score.get("summary"), dict):
        raise BaselinePublicationError(
            f"Baseline final score '{score_path}' has no 'summary' object; "
            "it was not written by code_coverage_baseline_score.py."
        )
    return score


def _phase_name(file_name: str) -> str:
    """`<workspace>.code-coverage-baseline-<phase>.json` -> `<phase>`."""
    task: str = file_name.removesuffix(".json").rsplit(".", 1)[-1]
    return task.removeprefix("code-coverage-baseline-").removeprefix("code-coverage-")


def load_token_usage(accounting_dir: str) -> list[dict[str, Any]]:
    """One row per Rhei task from per-task accounting, cheapest phase last.

    The guided arm sorts by a fixed phase order because its budget is split
    between named phases. The baseline spends one undifferentiated cover loop
    (§WF-code-coverage-baseline.3.3), so there is no meaningful workflow order
    to impose: rows are ordered by output tokens, which ranks the phases by what
    they actually cost. A phase with no accounting file is omitted rather than
    reported as zero.
    """
    tasks_dir: str = os.path.join(accounting_dir, "tasks")
    if not os.path.isdir(tasks_dir):
        return []
    rows: list[dict[str, Any]] = []
    for file_name in sorted(os.listdir(tasks_dir)):
        if not file_name.endswith(".json"):
            continue
        try:
            with open(os.path.join(tasks_dir, file_name), encoding="utf-8") as handle:
                direct: dict[str, Any] = json.load(handle)["direct"]
            rows.append({
                "phase": _phase_name(file_name),
                "input": direct["input_total"]["value"],
                "cached": direct["input_cached_read"]["value"],
                "output": direct["output_total"]["value"],
            })
        except (OSError, ValueError, KeyError, TypeError):
            continue
    rows.sort(key=lambda row: (-(row["output"] or 0), row["phase"]))
    return rows


def _token_cell(value: Any) -> str:
    """`n/a` keeps an unmeasured phase from reading as a free one."""
    return f"{value:,}" if isinstance(value, int) else "n/a"


def _token_lines(rows: list[dict[str, Any]]) -> list[str]:
    if not rows:
        return []
    lines: list[str] = [
        "## Token usage",
        "",
        "| Phase | Input | Input (cached) | Output |",
        "|---|--:|--:|--:|",
    ]
    totals: dict[str, int] = {"input": 0, "cached": 0, "output": 0}
    for row in rows:
        for key in totals:
            if isinstance(row[key], int):
                totals[key] += row[key]
        lines.append(
            f"| {row['phase']} | {_token_cell(row['input'])} | "
            f"{_token_cell(row['cached'])} | {_token_cell(row['output'])} |"
        )
    lines.append(
        f"| **Total** | **{totals['input']:,}** | "
        f"**{totals['cached']:,}** | **{totals['output']:,}** |"
    )
    lines += [
        "",
        "Input is uncached input tokens; Input (cached) is cache reads.",
    ]
    return lines


def _coverage_lines(score: dict[str, Any]) -> list[str]:
    """Baseline -> final coverage of the library-declared method universe."""
    summary: dict[str, Any] = score["summary"]
    total: int = summary["libraryMethods"]
    final_covered: int = summary["covered"]
    final_percent: float = summary["coveredPercent"]
    delta: dict[str, Any] = score.get("delta") or {}
    lines: list[str] = ["## Coverage", ""]
    if delta:
        baseline_covered: int = delta["baselineCovered"]
        baseline_percent: float = delta["baselineCoveredPercent"]
        lines += [
            f"- Baseline: {baseline_covered}/{total} ({baseline_percent}%)",
            f"- Final: {final_covered}/{total} ({final_percent}%)",
            f"- Delta: {_signed(round(final_percent - baseline_percent, 2))}pp "
            f"({_signed(delta['coveredDelta'])} methods)",
        ]
        if delta.get("libraryMethodsDelta"):
            lines.append(
                f"- Denominator moved by {_signed(delta['libraryMethodsDelta'])} "
                "methods between the two measurements."
            )
    else:
        lines += [
            f"- Final: {final_covered}/{total} ({final_percent}%)",
            "- Baseline: not recorded, so no delta is reported.",
        ]
    lines.append(f"- Remaining uncovered: {summary['uncovered']}")
    return lines


def _denominator_lines(score: dict[str, Any]) -> list[str]:
    """State the denominator, so the number is not read as raw JaCoCo coverage."""
    summary: dict[str, Any] = score["summary"]
    jars: list[str] = score.get("libraryJars") or []
    lines: list[str] = [
        "### Denominator",
        "",
        "The percentage above counts **library-declared methods only**: the "
        "JaCoCo method records whose declaring class is declared by the "
        "resolved main jar (§WF-code-coverage-baseline.3.2). It is not the raw "
        "JaCoCo percentage, which also counts every other instrumented class on "
        "the test runtime classpath.",
        "",
        f"- JaCoCo methods in report: {summary['jacocoMethods']}",
        f"- Excluded as non-library: {summary['nonLibraryMethodsExcluded']}",
        f"- Library methods (denominator): {summary['libraryMethods']}",
    ]
    if jars:
        selection: str = score.get("jarSelection", "unknown")
        names: str = ", ".join(f"`{os.path.basename(jar)}`" for jar in jars)
        lines.append(f"- Main jars ({selection}): {names}")
    return lines


def _package_lines(score: dict[str, Any]) -> list[str]:
    """Top packages by remaining uncovered methods: where coverage is still available."""
    packages: list[dict[str, Any]] = list(score.get("packages") or [])
    if not packages:
        return []
    packages.sort(key=lambda entry: (-entry["uncovered"], entry["package"]))
    shown: list[dict[str, Any]] = packages[:TOP_PACKAGE_COUNT]
    lines: list[str] = [
        f"### Top {len(shown)} packages by remaining uncovered methods",
        "",
        "| Package | Methods | Covered | Uncovered | % |",
        "|---|--:|--:|--:|--:|",
    ]
    lines += [
        f"| `{entry['package']}` | {entry['methods']} | {entry['covered']} | "
        f"{entry['uncovered']} | {entry['coveredPercent']} |"
        for entry in shown
    ]
    if len(packages) > len(shown):
        lines += ["", f"{len(packages) - len(shown)} further packages omitted."]
    return lines


def build_pull_request_body(
        coordinate: str,
        issue_number: int | None,
        score: dict[str, Any],
        coverage_suite_path: str,
        token_usage: list[dict[str, Any]] | None = None,
) -> str:
    """Build the PR body entirely from recorded evidence.

    The issue is linked without a closing keyword: this arm is an experiment
    that measures the guided arm, and does not resolve its issue.
    """
    lines: list[str] = ["## Unguided code coverage baseline", ""]
    if issue_number:
        lines.append(f"- Source issue: #{issue_number} (not resolved by this PR)")
    lines += [
        f"- Coordinate: `{coordinate}`",
        f"- Coverage suite path: `{coverage_suite_path}`",
        "",
        "This is the unguided control arm of §WF-code-coverage-baseline: the "
        "agent was given only the library's main jar and the JaCoCo report, "
        "with no inventory, ranking, eligibility filter, or phase split.",
        "",
    ]
    lines += _coverage_lines(score)
    lines += [""] + _denominator_lines(score)
    package_lines: list[str] = _package_lines(score)
    if package_lines:
        lines += [""] + package_lines
    token_lines: list[str] = _token_lines(token_usage or [])
    if token_lines:
        lines += [""] + token_lines
    return "\n".join(lines) + "\n"


def _coverage_commit_subject(coordinate: str) -> str:
    subject: str = f"Baseline code coverage for {coordinate}"
    if len(subject) <= MAX_COMMIT_SUBJECT_LENGTH:
        return subject
    return subject[:MAX_COMMIT_SUBJECT_LENGTH - 3] + "..."


def _relative_test_dir(
        repo_path: str,
        group: str,
        artifact: str,
        version: str,
) -> str | None:
    """The indexed tests/src directory, or None when the index cannot resolve it."""
    try:
        return os.path.relpath(
            resolve_test_dir(repo_path, group, artifact, version), repo_path
        )
    except Exception:  # pylint: disable=broad-except
        # The baseline suite path alone is still publishable; an unresolvable
        # index must not cost the run its evidence.
        return None


def stage_coverage_paths(
        repo_path: str,
        group: str,
        artifact: str,
        version: str,
        coverage_suite_path: str,
) -> None:
    """Stage the baseline coverage suite and commit it if anything changed."""
    candidates: list[str] = [coverage_suite_path]
    test_dir: str | None = _relative_test_dir(repo_path, group, artifact, version)
    if test_dir:
        candidates.append(test_dir)
    existing: list[str] = [
        path for path in candidates if os.path.exists(os.path.join(repo_path, path))
    ]
    if not existing:
        raise BaselinePublicationError(
            f"Nothing to stage: '{coverage_suite_path}' does not exist under {repo_path}."
        )
    stage_and_commit(
        existing,
        _coverage_commit_subject(f"{group}:{artifact}:{version}"),
        cwd=repo_path,
    )


def create_pull_request(
        repo_path: str,
        branch: str,
        coordinate: str,
        issue_number: int | None,
        score: dict[str, Any],
        coverage_suite_path: str,
        head_owner: str | None,
        base_branch: str,
        token_usage: list[dict[str, Any]] | None = None,
) -> int | None:
    """Open the PR on the fork; no reviewers and no labels exist there."""
    if shutil.which("gh") is None:
        print("gh CLI not found. Skipping PR creation.")
        return None
    owner: str = head_owner or get_origin_owner(cwd=repo_path)
    command: list[str] = [
        "pr",
        "create",
        "--repo",
        REPO,
        "--title",
        f"Baseline code coverage for {coordinate}",
        "--body",
        build_pull_request_body(
            coordinate, issue_number, score, coverage_suite_path, token_usage
        ),
        "--base",
        base_branch,
        "--head",
        f"{owner}:{branch}",
    ]
    result: subprocess.CompletedProcess[str] = gh(*command)
    return parse_pr_number(result.stdout)


def publish(
        repo_path: str,
        coordinate: str,
        issue_number: int | None,
        finalization_dir: str,
        coverage_suite_path: str,
        push_remote: str,
        head_owner: str | None,
        base_branch: str,
        accounting_dir: str | None = None,
) -> int | None:
    """Commit the produced suite, push it to the fork, and open the PR."""
    group: str
    artifact: str
    version: str
    group, artifact, version = coordinate.split(":")
    score: dict[str, Any] = load_final_score(finalization_dir)
    recorded: str = score.get("coordinate", coordinate)
    if recorded != coordinate:
        raise BaselinePublicationError(
            f"Final score coordinate is '{recorded}', expected '{coordinate}'."
        )

    branch: str = build_ai_branch_name(
        f"code-coverage-baseline-{artifact}-{version}", cwd=repo_path
    )
    delete_remote_branch_if_exists(branch, remote=push_remote, cwd=repo_path)
    subprocess.run(["git", "switch", "-C", branch], check=True, cwd=repo_path)
    stage_coverage_paths(repo_path, group, artifact, version, coverage_suite_path)
    run_git_transport(["push", push_remote, branch], cwd=repo_path)
    # Rhei writes accounting beside the workflow runtime, two levels above the
    # finalization directory it hands this helper.
    resolved_accounting: str = accounting_dir or os.path.join(
        finalization_dir, os.pardir, os.pardir, "accounting"
    )
    return create_pull_request(
        repo_path,
        branch,
        coordinate,
        issue_number,
        score,
        coverage_suite_path,
        head_owner,
        base_branch,
        load_token_usage(resolved_accounting),
    )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Publish the unguided code coverage baseline arm.",
        epilog=(
            "Example:\n"
            "  python3 git_scripts/make_pr_code_coverage_baseline.py "
            "--repo-path <worktree> --coordinate group:artifact:version "
            "--issue-number 8380 "
            "--finalization-dir runtime/code-coverage-baseline/finalization "
            "--coverage-suite-path tests/src/group/artifact/version/code-coverage-improvement"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    parser.add_argument(
        "--repo-path", required=True, help="Issue worktree / repository root."
    )
    parser.add_argument(
        "--coordinate", required=True, help="group:artifact:version."
    )
    parser.add_argument(
        "--issue-number", type=int, default=None, help="Backing GitHub issue."
    )
    parser.add_argument(
        "--finalization-dir",
        required=True,
        help=f"Directory containing {FINAL_SCORE_FILENAME}.",
    )
    parser.add_argument(
        "--coverage-suite-path",
        required=True,
        help="Repository-relative dedicated coverage suite path.",
    )
    parser.add_argument(
        "--push-remote", default=PUSH_REMOTE, help="Writable fork remote."
    )
    parser.add_argument(
        "--head-owner", default=PUSH_REMOTE, help="GitHub owner for the PR head."
    )
    parser.add_argument(
        "--base-branch", default=BASE_BRANCH, help="Pull request base branch."
    )
    parser.add_argument(
        "--accounting-dir",
        default=None,
        help="Rhei accounting directory; defaults beside the workflow runtime.",
    )
    args: argparse.Namespace = parser.parse_args(argv)
    try:
        pr_number: int | None = publish(
            args.repo_path,
            args.coordinate,
            args.issue_number,
            args.finalization_dir,
            args.coverage_suite_path,
            args.push_remote,
            args.head_owner,
            args.base_branch,
            args.accounting_dir,
        )
    except BaselinePublicationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    if pr_number:
        print(f"Opened PR #{pr_number} for {args.coordinate}.")


if __name__ == "__main__":
    main()
