# Rhei: Code Coverage Baseline (unguided control arm)
**States:** code-coverage-baseline

## Overview

This workspace converts `{{repo}}` issue `#{{issue_number}}` into the unguided
control arm of the code coverage workflow §WF-code-coverage-baseline. The issue
must carry `{{issue_label}}` and identify a Maven coordinate in
`group:artifact:version` form unless `{{coordinate}}` is provided.

It exists to answer one question: how much of the guided arm's measured coverage
gain is produced by its signal pipeline, and how much an agent would produce
from the raw report alone §WF-code-coverage-baseline.1.

The agent is given exactly two things about the library: the resolved main jar,
classifier-free, and the current JaCoCo XML report at the fixed path
`runtime/code-coverage-baseline/report/jacoco.xml`, rewritten before every pass
(§WF-code-coverage-baseline.3.1). It is given no API inventory, no unlock
ranking, no receiver-obtainability filter, no sampled-PGO routes, and no
API/deep phase split — those are precisely what this arm ablates
(§WF-code-coverage-baseline.4). It may read the jar, the extracted upstream
sources, and the existing metadata-generation tests as context, and may parse
the report however it likes.

Everything else is held equal with the guided arm so a difference between them
is a difference in signal: the same coordinate, the same worktree base commit
rule, the same tracked extension suite at
`tests/src/<group>/<artifact>/<test-version>/code-coverage-improvement`, the
same Gradle tasks, and the same finalization gate
(§WF-code-coverage-baseline.2). Runtime evidence is written under
`runtime/code-coverage-baseline/`; the score is written where the cover agent
cannot read it.

## Source

| Field | Value |
|---|---|
| Repository | `{{repo}}` |
| Issue | `#{{issue_number}}` |
| Required label | `{{issue_label}}` |
| Coordinate override | `{{coordinate}}` |
| Source checkout | `{{repo_checkout}}` |
| Worktree root | `{{worktree_root}}` |
| Work subdirectory | `{{work_subdir}}` |
| Worker agent | `{{worker_agent}}` |
| Cover pass budget | `{{coverage_iterations}}` |

## Verification

The pipeline tasks run unreviewed: deterministic helpers and zero-exit
validation gates decide their completion. The coverage loop is
measurement-driven — a deterministic measurement program runs `compileTestJava`,
`codeCoverageTest`, and `jacocoCodeCoverageReport`, always rewrites the current
JaCoCo report to one fixed location, scores the library universe outside the
directory the cover state reads, and alone decides whether the loop continues
(§WF-code-coverage-baseline.3.2). The budget is a fixed
`{{coverage_iterations}}` cover passes spent in one undifferentiated loop, and
the run also completes early when nothing uncovered remains
(§WF-code-coverage-baseline.3.3). No pass is credited except by re-measurement;
the agent cannot claim progress. Measurement step failures are repaired through
the bounded `fix` state, limited by `{{measure_visits}}` visits.

The finalization task runs as a deterministic program of numbered steps (read
the pipeline records, run checkstyle, run the JVM tests with the coverage suite,
re-score against iteration 0); a nonzero exit code is the number of the failed
step and routes to `finalize-fix`, after which the steps re-run from the start.
Fixable failures are bounded by `{{fix_passes}}` pass(es); anything still
failing routes to `human-intervention`. Publication is gated on that program
exiting zero. This workflow runs no Native Image validation at any point.

Coverage is reported over the JaCoCo methods whose declaring class the main jar
declares — the same basis the guided arm reports after excluding non-library
methods, so the two arms' percentages are directly comparable. Raw
`jacocoMethods` is context, not a coverage figure
(§WF-code-coverage-baseline.3.2).
