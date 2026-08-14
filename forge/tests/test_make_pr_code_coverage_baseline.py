# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

import json
import os
import tempfile
import unittest
from unittest.mock import patch

from git_scripts import make_pr_code_coverage_baseline as module


class BaselinePublisherTests(unittest.TestCase):

    def _score(self) -> dict:
        return {
            "coordinate": "com.example:demo:1.0.0",
            "iteration": 4,
            "libraryJars": ["/cache/demo-1.0.0.jar"],
            "jarSelection": "exact-name",
            "libraryClasses": 12,
            "summary": {
                "jacocoMethods": 500,
                "libraryMethods": 100,
                "nonLibraryMethodsExcluded": 400,
                "covered": 60,
                "uncovered": 40,
                "coveredPercent": 60.0,
            },
            "delta": {
                "baselineCovered": 25,
                "baselineCoveredPercent": 25.0,
                "coveredDelta": 35,
                "coveredPercentDelta": 35.0,
                "libraryMethodsDelta": 0,
            },
            "packages": [
                {
                    "package": f"com.example.p{index}",
                    "methods": 10,
                    "covered": 10 - index,
                    "uncovered": index,
                    "coveredPercent": float(100 - 10 * index),
                }
                for index in range(20)
            ],
        }

    def test_body_reports_coverage_delta(self) -> None:
        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, self._score(), "tests/src/suite"
        )

        self.assertIn("- Baseline: 25/100 (25.0%)", body)
        self.assertIn("- Final: 60/100 (60.0%)", body)
        self.assertIn("- Delta: +35.0pp (+35 methods)", body)
        self.assertIn("- Remaining uncovered: 40", body)
        self.assertIn("`com.example:demo:1.0.0`", body)
        self.assertIn("`tests/src/suite`", body)
        self.assertIn("unguided control arm of §WF-code-coverage-baseline", body)

    def test_body_states_the_non_library_exclusion(self) -> None:
        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, self._score(), "tests/src/suite"
        )

        self.assertIn("library-declared methods only", body)
        self.assertIn("- JaCoCo methods in report: 500", body)
        self.assertIn("- Excluded as non-library: 400", body)
        self.assertIn("- Library methods (denominator): 100", body)
        self.assertIn("`demo-1.0.0.jar`", body)

    def test_body_omits_issue_line_without_issue_number(self) -> None:
        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", None, self._score(), "tests/src/suite"
        )

        self.assertNotIn("Source issue", body)
        self.assertNotIn("#", body.split("## Coverage")[0].replace("## ", ""))

    def test_body_never_emits_a_closing_keyword(self) -> None:
        for issue_number in (8380, None):
            body = module.build_pull_request_body(
                "com.example:demo:1.0.0", issue_number, self._score(), "tests/src/suite"
            )

            for keyword in ("Fixes #", "Closes #", "Resolves #", "Fixed #"):
                self.assertNotIn(keyword, body)
        self.assertIn("#8380 (not resolved by this PR)", body := module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, self._score(), "tests/src/suite"
        ))
        self.assertIn("Source issue", body)

    def test_body_truncates_and_sorts_the_package_table(self) -> None:
        score = self._score()
        # Deliberately unsorted input: the body must not trust the input order.
        score["packages"].sort(key=lambda entry: entry["package"])

        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, score, "tests/src/suite"
        )

        table_rows = [line for line in body.splitlines() if line.startswith("| `com.example.p")]
        self.assertEqual(len(table_rows), module.TOP_PACKAGE_COUNT)
        uncovered = [int(row.split("|")[4].strip()) for row in table_rows]
        self.assertEqual(uncovered, sorted(uncovered, reverse=True))
        self.assertEqual(uncovered[0], 19)
        self.assertIn("5 further packages omitted.", body)

    def test_body_reports_final_only_without_a_delta(self) -> None:
        score = self._score()
        del score["delta"]

        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, score, "tests/src/suite"
        )

        self.assertIn("- Final: 60/100 (60.0%)", body)
        self.assertIn("Baseline: not recorded", body)
        self.assertNotIn("- Delta:", body)

    def test_body_renders_token_usage_when_accounting_exists(self) -> None:
        usage = [
            {"phase": "cover", "input": 5000, "cached": 6000, "output": 70},
            {"phase": "prepare", "input": 1000, "cached": 2000, "output": 30},
        ]

        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, self._score(), "tests/src/suite", usage
        )

        self.assertIn("## Token usage", body)
        self.assertIn("| cover | 5,000 | 6,000 | 70 |", body)
        self.assertIn("| prepare | 1,000 | 2,000 | 30 |", body)
        self.assertIn("| **Total** | **6,000** | **8,000** | **100** |", body)

    def test_body_omits_token_section_without_accounting(self) -> None:
        body = module.build_pull_request_body(
            "com.example:demo:1.0.0", 8380, self._score(), "tests/src/suite", []
        )

        self.assertNotIn("## Token usage", body)

    def test_token_usage_ranks_by_cost_and_marks_unmeasured(self) -> None:
        with tempfile.TemporaryDirectory() as accounting:
            tasks = os.path.join(accounting, "tasks")
            os.makedirs(tasks)
            for phase, output in (("prepare", 30), ("cover", 700), ("finalize", None)):
                with open(
                        os.path.join(tasks, f"ws.code-coverage-baseline-{phase}.json"),
                        "w",
                        encoding="utf-8",
                ) as handle:
                    json.dump({"direct": {
                        "input_total": {"value": 3},
                        "input_cached_read": {"value": 6},
                        "output_total": {"value": output},
                    }}, handle)

            rows = module.load_token_usage(accounting)

        self.assertEqual([row["phase"] for row in rows], ["cover", "prepare", "finalize"])
        self.assertIn("| finalize | 3 | 6 | n/a |", "\n".join(module._token_lines(rows)))

    def test_token_usage_is_empty_without_accounting_directory(self) -> None:
        with tempfile.TemporaryDirectory() as empty:
            self.assertEqual(module.load_token_usage(empty), [])

    def test_missing_final_score_raises_a_clear_error(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-pr-") as directory:
            with self.assertRaises(module.BaselinePublicationError) as raised:
                module.load_final_score(directory)

        self.assertIn("final-score.json", str(raised.exception))

    def test_final_score_without_summary_raises(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-pr-") as directory:
            with open(
                    os.path.join(directory, "final-score.json"), "w", encoding="utf-8"
            ) as score_file:
                json.dump({"coordinate": "com.example:demo:1.0.0"}, score_file)

            with self.assertRaises(module.BaselinePublicationError) as raised:
                module.load_final_score(directory)

        self.assertIn("no 'summary'", str(raised.exception))

    def test_load_final_score_returns_the_report(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-pr-") as directory:
            with open(
                    os.path.join(directory, "final-score.json"), "w", encoding="utf-8"
            ) as score_file:
                json.dump(self._score(), score_file)

            loaded = module.load_final_score(directory)

        self.assertEqual(loaded["summary"]["libraryMethods"], 100)

    def test_commit_subject_is_at_most_sixty_characters(self) -> None:
        coordinate = f"com.{'verylong.' * 10}:artifact-with-long-name:1.0.0"

        subject = module._coverage_commit_subject(coordinate)

        self.assertEqual(len(subject), module.MAX_COMMIT_SUBJECT_LENGTH)
        self.assertTrue(subject.startswith("Baseline code coverage for "))
        self.assertTrue(subject.endswith("..."))

    def test_stages_the_coverage_suite(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-stage-") as repo_path:
            coverage_suite = os.path.join(
                "tests", "src", "com.example", "demo", "1.0.0", "code-coverage-improvement"
            )
            os.makedirs(os.path.join(repo_path, coverage_suite))

            with patch.object(module, "stage_and_commit") as stage_and_commit:
                module.stage_coverage_paths(
                    repo_path, "com.example", "demo", "1.0.0", coverage_suite
                )

        staged_paths, subject = stage_and_commit.call_args.args
        self.assertIn(coverage_suite, staged_paths)
        self.assertEqual(subject, "Baseline code coverage for com.example:demo:1.0.0")
        self.assertEqual(stage_and_commit.call_args.kwargs["cwd"], repo_path)

    def test_staging_nothing_raises(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-stage-") as repo_path:
            with self.assertRaises(module.BaselinePublicationError):
                module.stage_coverage_paths(
                    repo_path, "com.example", "demo", "1.0.0", "tests/src/missing"
                )

    def test_pull_request_targets_the_fork_without_labels(self) -> None:
        class Result:
            stdout = "https://github.com/kimeta/graalvm-reachability-metadata/pull/17"

        with patch.object(module.shutil, "which", return_value="/usr/bin/gh"):
            with patch.object(module, "gh", return_value=Result()) as gh_mock:
                pr_number = module.create_pull_request(
                    "/repo",
                    "ai/kimeta/code-coverage-baseline-demo-1.0.0",
                    "com.example:demo:1.0.0",
                    8380,
                    self._score(),
                    "tests/src/suite",
                    "kimeta",
                    "master",
                )

        self.assertEqual(pr_number, 17)
        command = list(gh_mock.call_args.args)
        self.assertEqual(command[command.index("--repo") + 1], module.REPO)
        self.assertEqual(command[command.index("--base") + 1], "master")
        self.assertEqual(
            command[command.index("--head") + 1],
            "kimeta:ai/kimeta/code-coverage-baseline-demo-1.0.0",
        )
        self.assertNotIn("--label", command)
        self.assertNotIn("--reviewer", command)

    def test_pull_request_is_skipped_without_gh(self) -> None:
        with patch.object(module.shutil, "which", return_value=None):
            with patch.object(module, "gh") as gh_mock:
                pr_number = module.create_pull_request(
                    "/repo",
                    "branch",
                    "com.example:demo:1.0.0",
                    8380,
                    self._score(),
                    "tests/src/suite",
                    "kimeta",
                    "master",
                )

        self.assertIsNone(pr_number)
        gh_mock.assert_not_called()

    def test_publish_pushes_to_the_fork_remote(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-publish-") as repo_path:
            finalization = os.path.join(repo_path, "finalization")
            os.makedirs(finalization)
            with open(
                    os.path.join(finalization, "final-score.json"), "w", encoding="utf-8"
            ) as score_file:
                json.dump(self._score(), score_file)
            coverage_suite = os.path.join("tests", "src", "suite")
            os.makedirs(os.path.join(repo_path, coverage_suite))

            with patch.object(module, "build_ai_branch_name", return_value="ai/kimeta/b"), \
                    patch.object(module, "delete_remote_branch_if_exists"), \
                    patch.object(module.subprocess, "run"), \
                    patch.object(module, "stage_and_commit"), \
                    patch.object(module, "run_git_transport") as transport, \
                    patch.object(module, "create_pull_request", return_value=42) as create:
                pr_number = module.publish(
                    repo_path,
                    "com.example:demo:1.0.0",
                    8380,
                    finalization,
                    coverage_suite,
                    "kimeta",
                    "kimeta",
                    "master",
                )

        self.assertEqual(pr_number, 42)
        self.assertEqual(transport.call_args.args[0], ["push", "kimeta", "ai/kimeta/b"])
        self.assertEqual(create.call_args.args[1], "ai/kimeta/b")

    def test_publish_rejects_a_mismatched_coordinate(self) -> None:
        with tempfile.TemporaryDirectory(prefix="baseline-publish-") as repo_path:
            finalization = os.path.join(repo_path, "finalization")
            os.makedirs(finalization)
            with open(
                    os.path.join(finalization, "final-score.json"), "w", encoding="utf-8"
            ) as score_file:
                json.dump(self._score(), score_file)

            with self.assertRaises(module.BaselinePublicationError):
                module.publish(
                    repo_path,
                    "com.example:other:1.0.0",
                    8380,
                    finalization,
                    "tests/src/suite",
                    "kimeta",
                    "kimeta",
                    "master",
                )

    def test_defaults_target_the_fork(self) -> None:
        self.assertEqual(module.REPO, "kimeta/graalvm-reachability-metadata")
        self.assertEqual(module.PUSH_REMOTE, "kimeta")
        self.assertEqual(module.BASE_BRANCH, "master")


if __name__ == "__main__":
    unittest.main()
