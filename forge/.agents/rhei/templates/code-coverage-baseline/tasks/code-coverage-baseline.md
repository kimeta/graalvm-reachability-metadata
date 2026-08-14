### Task code-coverage-baseline-convert: Convert issue {{issue_number}}
**State:** prepared

- Source issue: `https://github.com/{{repo}}/issues/{{issue_number}}`
- Repository: `{{repo}}`
- Required label: `{{issue_label}}`
- Coordinate override: `{{coordinate}}`
- Source checkout: `{{repo_checkout}}`
- Worktree root: `{{worktree_root}}`
- Work subdirectory: `{{work_subdir}}`
- Purpose: fetch one `{{issue_label}}` issue and create or reuse the per-issue
  worktree for the unguided control arm §WF-code-coverage-baseline.
- Required work:
  - Fetch the issue with `gh issue view {{issue_number}} --repo {{repo}}`.
  - Verify that it carries `{{issue_label}}`.
  - If `{{coordinate}}` is non-empty, use it as the coordinate; otherwise parse
    exactly one `group:artifact:version` coordinate from the issue body.
  - Create or reuse one worktree below `{{worktree_root}}` for the issue using a
    branch name like `rhei/code-coverage-baseline-issue-{{issue_number}}-<slug>`,
    with its branch created from the HEAD of `{{repo_checkout}}` — never from
    `origin/master`. The workflow's own helpers are resolved from the issue
    worktree, so an older base fails measurement outright
    (§WF-code-coverage-baseline.2).
  - Record the resolved worktree and work path, where work path is the worktree
    joined with `{{work_subdir}}`.
  - Write `runtime/code-coverage-baseline/issues/conversion.json` with exactly
    these fields: `coordinate`, `worktreePath`, `workPath`,
    `coverageSuiteAbsolutePath`, and `coverageSuiteRepoRelativePath`
    (`tests/src/<group>/<artifact>/<test-version>/code-coverage-improvement`,
    where `<test-version>` is the indexed test project directory that covers the
    coordinate — resolve it with
    `utility_scripts.metadata_index.resolve_test_dir`). The suite location is
    the guided arm's, so the two arms' produced tests are interchangeable
    (§WF-code-coverage-baseline.2). The deterministic measurement and
    finalization programs read this record; all paths except the
    repository-relative suite path must be absolute.
  - This template has no GitHub Project inputs. Do not read or move any Project
    item status.
- Artifacts:
  - `runtime/code-coverage-baseline/issues/conversion.json`
  - `runtime/code-coverage-baseline/work/code-coverage-baseline-{{issue_number}}.code-coverage-baseline-convert.md`

### Task code-coverage-baseline-prepare: Prepare library sources
**State:** prepared
**Prior:** Task code-coverage-baseline-convert

- Source artifact: `runtime/code-coverage-baseline/issues/conversion.json`
- Helper script: `forge/utility_scripts/code_coverage_baseline_sources.py`
- Purpose: resolve the library's main jar and extracted sources, and create or
  verify the tracked coverage suite. Nothing is analysed, ranked, or filtered.
- Required work:
  - Read the resolved coordinate, worktree, and work path from the conversion
    record.
  - Run the helper once, from the issue worktree:

    ```bash
    python3 utility_scripts/code_coverage_baseline_sources.py \
      --repo-path <worktreePath> --coordinate <coordinate> \
      --output-dir runtime/code-coverage-baseline/prepare \
      --sources-dir runtime/code-coverage-baseline/sources
    ```

  - Verify the helper wrote
    `runtime/code-coverage-baseline/prepare/library.json` with `coordinate`,
    `libraryJars` (the classifier-free main jar — a `test`-classifier artifact
    of the library's own unit tests must never be handed to the agent,
    §WF-code-coverage-baseline.3.1), `jarSelection`, `sourcesAvailable`,
    `sourcesJar`, `sourcesDir`, `javaFileCount`, and `caveats`. If it records no
    jar, measurement cannot score anything: record the blocker and request
    `human-intervention`.
  - Create or verify the tracked extension suite at `code-coverage-improvement/`
    inside the resolved test project, including `src/test/java` and optional
    `src/test/resources` below that suite root, plus a `suite.json` recording
    the true `coordinates` being improved. Do not mutate the
    metadata-generation tests under the regular `src/test`.
- This task must NOT analyse the library, build any inventory, rank anything,
  compute eligibility, or prepare coverage targets. Preparation resolves paths
  and artifacts only; the signal stack is exactly what this arm ablates
  (§WF-code-coverage-baseline.3.1).
- Artifacts:
  - `runtime/code-coverage-baseline/prepare/library.json`
  - `runtime/code-coverage-baseline/work/code-coverage-baseline-{{issue_number}}.code-coverage-baseline-prepare.md`

### Task code-coverage-baseline-coverage: Unguided coverage loop
**State:** measure
**Prior:** Task code-coverage-baseline-prepare

- Measurement program, driven by the `measure` state in numbered steps: the
  Gradle tasks `compileTestJava`, `codeCoverageTest`, and
  `jacocoCodeCoverageReport`, followed by
  `forge/utility_scripts/code_coverage_baseline_score.py` (step 3).
- Fixed report location: `runtime/code-coverage-baseline/report/jacoco.xml`,
  rewritten by measurement before every cover pass; iteration history stays at
  `jacoco-<n>.xml`. This XML plus the main jar named in `library.json` is
  everything the cover agent is given (§WF-code-coverage-baseline.3.1).
- Score location: `runtime/code-coverage-baseline/score/baseline-report-<n>.json`
  and `.md`, in a directory the `cover` state is deliberately not told about, so
  the agent never sees its own score and cannot optimize the number being
  measured (§WF-code-coverage-baseline.3.2).
- Denominator: the methods a JaCoCo report mentions, intersected with the
  classes the selected main jars declare. Raw `jacocoMethods` is recorded as
  context only and is not a coverage figure — on kafka-streams the library's own
  `test`-classifier artifact alone contributed 12271 of 15413 apparent targets
  (§WF-code-coverage-baseline.3.1).
- Loop: measure -> cover -> measure, with a fixed budget of
  `{{coverage_iterations}}` cover passes spent in one undifferentiated loop;
  there is no API/deep phase split to make. Measurement always rewrites the
  report at the fixed path and always decides the loop: it completes the run
  when nothing uncovered remains or the budget is spent, and otherwise sends the
  agent back to `cover`. Only re-measurement moves the loop forward; the agent
  cannot claim progress (§WF-code-coverage-baseline.3.3). Failed measurement
  steps route to the bounded `fix` state and re-measure from the start.
- Artifacts:
  - `runtime/code-coverage-baseline/report/jacoco.xml`
  - `runtime/code-coverage-baseline/score/baseline-report-<n>.json`
  - `runtime/code-coverage-baseline/work/code-coverage-baseline-{{issue_number}}.code-coverage-baseline-coverage.md`

### Task code-coverage-baseline-finalization: Finalize validation and score
**State:** finalize
**Prior:** Task code-coverage-baseline-coverage

- Helper script: `forge/utility_scripts/code_coverage_baseline_score.py`
- Purpose: gate publication on deterministic post-loop validation and produce
  the final, comparable score for the arm.
- Execution: this task is a deterministic program of numbered steps, not an
  agent checklist. A nonzero exit code is the number of the failed step and
  routes to `finalize-fix`, after which the steps re-run from the start.
  Finalization runs no Native Image validation; this workflow runs none at any
  point (§WF-code-coverage-baseline.4).
- Program steps:
  1. Read `runtime/code-coverage-baseline/issues/conversion.json` for the
     resolved coordinate, worktree, and work path, and
     `runtime/code-coverage-baseline/prepare/library.json` for `libraryJars`.
  2. Run checkstyle over the coordinate's subprojects, including the tracked
     coverage suite source set:
     `./gradlew checkstyle -Pcoordinates=<resolved coordinate> --stacktrace`.
  3. Run the regular JVM tests and the tracked extension suite:
     `./gradlew javaTest -Pcoordinates=<resolved coordinate> --stacktrace` and
     `./gradlew codeCoverageTest -Pcoordinates=<resolved coordinate> --stacktrace`.
     This is the same finalization gate the guided arm applies, held equal so a
     difference between the arms is a difference in signal
     (§WF-code-coverage-baseline.5).
  4. Re-score with `--skip-gradle` against
     `runtime/code-coverage-baseline/score/baseline-report-0.json`, so the final
     figure is stated against iteration 0 without re-running the tasks.
- Artifacts:
  - `runtime/code-coverage-baseline/finalization/final-score.json`
  - `runtime/code-coverage-baseline/finalization/final-summary.md`
  - `runtime/code-coverage-baseline/work/code-coverage-baseline-{{issue_number}}.code-coverage-baseline-finalization.md`

### Task code-coverage-baseline-publication: Publish pull request
**State:** prepared
**Prior:** Task code-coverage-baseline-finalization

- Helper script: `forge/git_scripts/make_pr_code_coverage_baseline.py`
- Purpose: publish the control arm's run as an experimental pull request on the
  personal fork, so the two arms' suites can be compared side by side
  (§WF-code-coverage-baseline.5).
- Required work:
  - Read `runtime/code-coverage-baseline/finalization/final-score.json` and
    `final-summary.md`.
  - Confirm the issue worktree branch is the expected issue branch, and create a
    focused commit if verified changes are uncommitted.
  - Invoke the helper with `--repo-path`, `--coordinate`, `--issue-number`,
    `--finalization-dir runtime/code-coverage-baseline/finalization`,
    `--coverage-suite-path <coverageSuiteRepoRelativePath>` from the conversion
    record, and `--accounting-dir` pointing at the Rhei accounting directory.
  - The helper publishes to the hardcoded personal fork
    `kimeta/graalvm-reachability-metadata`, requests no reviewers, and applies no
    labels. This arm is an experiment, not a contribution.
  - The helper writes the whole PR body from `final-score.json` and the
    accounting directory. Do not hand-write any of its sections: a section an
    agent types is one the next run silently drops.
  - Link the source issue without a closing keyword. The control arm does not
    resolve the issue.
- Artifacts:
  - `runtime/code-coverage-baseline/publication/pr.md`
  - `runtime/code-coverage-baseline/work/code-coverage-baseline-{{issue_number}}.code-coverage-baseline-publication.md`
