# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

import os
import re
import unittest

import yaml

TEMPLATE_DIR: str = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    ".agents", "rhei", "templates", "code-coverage-baseline",
)
PLACEHOLDER: re.Pattern = re.compile(r"\{\{([a-z_]+)\}\}")


def read_states() -> tuple[dict, str]:
    """Return the rendered baseline state machine and its raw source."""
    with open(os.path.join(TEMPLATE_DIR, "states.yaml"), encoding="utf-8") as states_file:
        source: str = states_file.read()
    rendered: str = source
    for placeholder, value in (
        ("{{measure_visits}}", "16"),
        ("{{coverage_iterations}}", "10"),
        ("{{fix_passes}}", "2"),
        ("{{worker_agent}}", "test-agent"),
        ("{{work_subdir}}", "forge"),
        ("{{issue_number}}", "8380"),
    ):
        rendered = rendered.replace(placeholder, value)
    return yaml.safe_load(rendered), source


def read_template() -> dict:
    """Return the baseline template descriptor."""
    with open(os.path.join(TEMPLATE_DIR, "template.yaml"), encoding="utf-8") as template_file:
        return yaml.safe_load(template_file)


class BaselineTemplateTests(unittest.TestCase):

    def setUp(self) -> None:
        self.machine, self.source = read_states()
        self.template = read_template()
        self.states = self.machine["states"]

    def test_every_placeholder_is_a_declared_input(self) -> None:
        declared: set[str] = {entry["name"] for entry in self.template["inputs"]}
        used: set[str] = set(PLACEHOLDER.findall(self.source))

        self.assertEqual(set(), used - declared, "states.yaml uses undeclared inputs")

    def test_transitions_only_name_declared_states(self) -> None:
        for transition in self.machine["transitions"]:
            with self.subTest(transition=transition["description"]):
                if transition["from"] != "*":
                    self.assertIn(transition["from"], self.states)
                self.assertIn(transition["to"], self.states)

    def test_reenterable_fix_states_have_visit_scoped_outputs(self) -> None:
        for state_name in ("fix", "finalize-fix"):
            with self.subTest(state=state_name):
                state: dict = self.states[state_name]
                self.assertGreater(state["visits"], 1)
                self.assertTrue(state["outputs"])
                for output in state["outputs"]:
                    self.assertIn("{visit_count}", output["path"])

    def test_every_measurement_exit_code_is_routed(self) -> None:
        # The measure program exits 0, 10, or the number of the failed step.
        # An unrouted code strands the loop, so each one needs both a repair
        # edge and an exhausted-budget edge (§WF-code-coverage-baseline.3.3).
        routed: set[int] = {
            transition["exit_code"]
            for transition in self.machine["transitions"]
            if transition["from"] == "measure" and "exit_code" in transition
        }
        self.assertEqual({0, 10, 1, 2, 3}, routed)

        for exit_code in (1, 2, 3):
            targets: set[str] = {
                transition["to"]
                for transition in self.machine["transitions"]
                if transition["from"] == "measure" and transition.get("exit_code") == exit_code
            }
            self.assertEqual({"fix", "human-intervention"}, targets)

    def test_the_cover_state_is_given_no_score_artifact(self) -> None:
        # The neutral score must stay outside what the agent reads, so the arm
        # cannot optimize the number being measured (§WF-code-coverage-baseline.3.2).
        cover: dict = self.states["cover"]
        rendered: str = yaml.safe_dump(cover)

        self.assertNotIn("code-coverage-baseline/score", rendered)
        self.assertEqual(
            ["runtime/code-coverage-baseline/report/jacoco.xml"],
            [entry["path"] for entry in cover["inputs"]],
        )

    def test_the_loop_carries_a_bounded_budget(self) -> None:
        self.assertEqual(10, self.states["cover"]["visits"])
        self.assertEqual(16, self.states["measure"]["visits"])

    def test_no_phase_split_survives_from_the_guided_arm(self) -> None:
        # The ablation is only meaningful if none of the guided arm's signal
        # states leaked in (§WF-code-coverage-baseline.4).
        for absent in ("api-measure", "api-cover", "deep-measure", "deep-cover"):
            self.assertNotIn(absent, self.states)
        # Prose may name the dropped components; artifact paths may not, since
        # a path is what would actually carry the signal into a state.
        paths: list[str] = [
            entry["path"]
            for state in self.states.values()
            for key in ("inputs", "outputs")
            for entry in state.get(key, [])
        ]
        for path in paths:
            self.assertTrue(
                path.startswith("runtime/code-coverage-baseline/"),
                f"state artifact escapes the baseline runtime: {path}",
            )
        for signal in ("api-inventory", "graph/", "discovery/", "prompts/"):
            self.assertNotIn(signal, " ".join(paths))


if __name__ == "__main__":
    unittest.main()
