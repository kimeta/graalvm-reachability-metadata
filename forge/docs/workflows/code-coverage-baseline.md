# WF-code-coverage-baseline: Unguided code coverage baseline workflow

The unguided baseline is the control arm for §WF-code-coverage-improvement. It
asks the same agent, for the same coordinate, to raise JaCoCo coverage of the
same library — but hands it only the library's main jar and the JaCoCo report,
with no inventory, no ranking, no eligibility filter, no sampled-PGO routes, and
no phase split.

It exists to answer one question: how much of the measured coverage gain is
produced by the signal pipeline, and how much would an agent produce from the
raw report alone.

## 1. Purpose

§WF-code-coverage-improvement builds a large signal stack — a public API
inventory, a bytecode call graph, receiver-obtainability filtering, unlock
ranking, and sampled-PGO routes — and spends a fixed pass budget on prompts
derived from it. Recorded runs show that stack explains a minority of the
outcome: on `org.apache.kafka:kafka-streams:3.6.2` the first API pass covered
289 public methods, of which 42 were prompt targets and 176 fell in classes the
prompt never named.

That measurement compares the pipeline against itself. It cannot say whether the
pipeline beats no pipeline, because no run without one exists. This workflow
produces that run.

## 2. Scope

The baseline shares everything with §WF-code-coverage-improvement except the
signal:

- the same coordinate, worktree, and worktree base commit rule
  (§WF-code-coverage-improvement.2);
- the same tracked extension suite at
  `tests/src/<group>/<artifact>/<test-version>/code-coverage-improvement`, so the
  produced tests are interchangeable with the guided arm's;
- the same Gradle tasks — `compileTestJava`, `codeCoverageTest`,
  `jacocoCodeCoverageReport` — and the same rule that the regular `src/test`
  sources are read-only;
- the same finalization gate: checkstyle and the JVM tests must pass.

It does not publish a pull request. The work product is the generated suite plus
the score artifacts; publication is a separate manual decision, because the
guided arm's PR body is assembled from per-phase metrics this arm does not have.

## 3. The workflow

### 3.1 What the agent is given

Exactly two things, plus the paths it needs to write to:

1. the resolved **main library jar** — the artifact named
   `<artifact>-<version>.jar`, with no classifier;
2. the current **JaCoCo XML report** at a fixed path, rewritten by measurement
   before every pass.

Nothing else about the library is computed for it. The agent may read the jar,
the library's upstream sources, and the existing metadata-generation tests as
context, and may parse the JaCoCo report however it likes.

The classifier exclusion is load-bearing rather than cosmetic. A library may
publish a `test`-classifier artifact of its own unit tests that resolves onto the
test runtime classpath and therefore appears in the JaCoCo report; on
kafka-streams that artifact contributed 12271 of 15413 apparent targets
(§WF-code-coverage-improvement.3.2). Handing it to the agent would point this
workflow at the library's own test code.

### 3.2 Measurement and scoring

`utility_scripts/code_coverage_baseline_score.py` runs the three Gradle tasks,
locates the combined JaCoCo report, and splits its method records by declaring
class:

> **Definition 1 (library methods).** With `J` the methods a JaCoCo report
> mentions and `Cls(j)` the classes the selected main jars declare,
> `L = { m ∈ J : owner(m) ∈ Cls(j) }`.

`L` is the denominator, and `|L ∩ covered|` the numerator. This is the same basis
the guided arm reports after excluding non-library methods, so the two arms'
percentages are directly comparable; raw `jacocoMethods` is not comparable and is
recorded only as context.

The score is written outside the directory the cover agent reads, so the agent
never sees its own score and cannot optimize the reported number directly.

### 3.3 The loop

```text
measure -- 0 (budget spent or nothing uncovered) --> completed
   |  ^  \-- 10 (uncovered library methods remain) --> cover --+
   |  +--------------------------------------------------------+
   +-- step failed (exit 1-3) --> fix --> measure
```

Measurement is a deterministic program: it always rewrites the report at the
fixed path and always decides the loop. The agent cannot claim progress, and no
pass is credited except by re-measurement — the one property the baseline keeps
from the guided arm, because it is soundness, not guidance.

The budget is a constant `coverage_iterations` (default 10) cover passes, spent
in one undifferentiated loop. The guided arm splits its budget between an API
phase and a deep phase; the baseline has no such distinction to make.

## 4. What is deliberately absent

Each of these is a component of §WF-code-coverage-improvement that the baseline
ablates, and the reason it is worth ablating:

| Absent | Guided arm's claim | Why the ablation is informative |
| --- | --- | --- |
| API inventory | Public entries are the actionable surface | 61% of gain landed in classes the prompt never named |
| Unlock ranking | Order by unlocked internal code | Score is not correlated with what gets covered |
| Receiver obtainability | Drop targets no test can invoke | Rejects 471 of 1847, 301 of them one adapter class |
| Sampled-PGO routes | Show how to reach internal code | Only meaningful once routes stopped being fabricated |
| Phase split | Public API first, internals second | Requires a native-image metadata step the baseline skips |

## 5. Comparison protocol

A result is only interpretable when the arms differ in signal alone. Hold equal:
coordinate, worker agent, worktree base commit, suite location, and the
finalization gate. Report:

- coverage of `L` at iteration 0 and after the last pass, for both arms;
- token cost and wall time, which the guided arm already publishes per phase;
- test-suite shape — classes added, assertions per test — because an arm can
  raise the numerator with shallow tests, and the repository's own review rules
  reject exactly that.

One run per arm on one coordinate settles nothing but a large difference. The
guided arm was tuned on kafka-streams and is expected to be favored there, so a
coordinate that never drove that tuning belongs in any conclusion.
