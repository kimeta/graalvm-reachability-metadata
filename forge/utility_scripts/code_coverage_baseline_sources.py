# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

"""
Prepare step of the unguided code coverage baseline (§WF-code-coverage-baseline.3.1).

It resolves two things for one coordinate and records them in a single
deterministic file the Rhei state machine reads:

- the binary main jar, which exists only to be the scoring denominator
  (§WF-code-coverage-baseline.3.2) and is never itself the agent's guidance;
- the library's `-sources.jar`, extracted to a directory, which is the only
  library context the cover agent is given.

The classifier-free selection is delegated to `select_main_jars`, because
dropping a `test`-classifier artifact is load-bearing rather than cosmetic
(§WF-code-coverage-baseline.3.1).

Sources are resolved through a throwaway Gradle project rather than a hardcoded
Maven Central URL: the tested libraries resolve from `mavenLocal()`,
`mavenCentral()` and the Confluent repository, and `listLibraryJars` resolves
`testRuntimeClasspath`, which never carries a sources artifact. A library that
publishes no sources jar is not a failure; it is a recorded caveat.

Usage:
  python3 utility_scripts/code_coverage_baseline_sources.py \
    --repo-path <issue worktree> --coordinate group:artifact:version \
    --output-dir runtime/code-coverage-baseline/prepare \
    --sources-dir runtime/code-coverage-baseline/sources
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile

from utility_scripts.code_coverage_baseline_score import BaselineScoreError, select_main_jars
from utility_scripts.gradle_environment import gradle_command_environment
from utility_scripts.gradle_test_runner import run_gradle_test_command

# Repositories the tested-library build declares; a sources artifact must be
# resolvable from the same set (`org.graalvm.internal.tck.gradle`).
REPOSITORY_URLS: tuple[str, ...] = ("https://packages.confluent.io/maven/",)

# Gradle wording for "this artifact does not exist", as opposed to a wrapper or
# build failure, which must stay fatal.
_MISSING_ARTIFACT_MARKERS: tuple[str, ...] = (
    "Could not find",
    "Could not resolve",
    "no matching variant",
)

_SOURCES_RESOLUTION_TIMEOUT_SECONDS: int = 15 * 60


class BaselineSourcesError(RuntimeError):
    """Raised when the baseline prepare step cannot produce a usable record."""


def parse_jar_lines(text: str) -> list[str]:
    """Keep only lines of Gradle output that name an existing `.jar` file.

    Gradle prints task output interleaved with progress and warning lines even
    under `--quiet`, so the parse is defensive by existence rather than by
    position (§WF-code-coverage-baseline.3.1).
    """
    jars: list[str] = []
    for raw_line in text.splitlines():
        line: str = raw_line.strip()
        if line.endswith(".jar") and os.path.isfile(line) and line not in jars:
            jars.append(line)
    return jars


def list_library_jars(repo_path: str, coordinate: str) -> list[str]:
    """Resolve the tested library's runtime jars through the repo's Gradle task."""
    command: str = (
        f"./gradlew listLibraryJars {shlex.quote(f'-Pcoordinates={coordinate}')} --quiet"
    )
    output: str = run_gradle_test_command(command, working_dir=repo_path, library=coordinate)
    jars: list[str] = parse_jar_lines(output)
    if not jars:
        raise BaselineSourcesError(
            f"'{command}' resolved no library jars for '{coordinate}'. Output tail:\n"
            f"{output[-2000:]}"
        )
    return jars


def _sources_project_files(group: str, artifact: str, version: str) -> dict[str, str]:
    """Return the file contents of the throwaway sources-resolution project."""
    repositories: str = "\n".join(
        f'    maven {{ url = uri("{url}") }}' for url in REPOSITORY_URLS
    )
    build_gradle: str = f"""
plugins {{
    id 'base'
}}

repositories {{
    mavenLocal()
    mavenCentral()
{repositories}
}}

configurations {{
    sourcesJar
}}

dependencies {{
    sourcesJar "{group}:{artifact}:{version}:sources"
}}

tasks.register("printSourcesJar") {{
    doLast {{
        configurations.sourcesJar.files.each {{ File file ->
            println file.absolutePath
        }}
    }}
}}
"""
    return {
        "settings.gradle": 'rootProject.name = "baseline-sources-resolver"\n',
        "build.gradle": build_gradle.lstrip(),
    }


def resolve_sources_jar(repo_path: str, group: str, artifact: str, version: str) -> str | None:
    """Resolve `group:artifact:version:sources`, or return None if none is published.

    The repo's own wrapper is used so the Gradle version and caches match the
    build that produced the binary jars.
    """
    gradlew: str = os.path.join(os.path.abspath(repo_path), "gradlew")
    if not os.path.isfile(gradlew):
        raise BaselineSourcesError(f"Gradle wrapper '{gradlew}' does not exist.")

    with tempfile.TemporaryDirectory(prefix="baseline-sources-") as project_dir:
        for name, content in _sources_project_files(group, artifact, version).items():
            with open(os.path.join(project_dir, name), "w", encoding="utf-8") as project_file:
                project_file.write(content)
        result = subprocess.run(
            [gradlew, "printSourcesJar", "--quiet", "--console=plain"],
            cwd=project_dir,
            env=gradle_command_environment(repo_path),
            check=False,
            capture_output=True,
            text=True,
            timeout=_SOURCES_RESOLUTION_TIMEOUT_SECONDS,
        )
    combined: str = f"{result.stdout}\n{result.stderr}"
    if result.returncode != 0:
        # A missing sources artifact is an expected outcome; anything else
        # (wrapper, JVM, network, build failure) must not be silently degraded.
        if any(marker in combined for marker in _MISSING_ARTIFACT_MARKERS):
            return None
        raise BaselineSourcesError(
            f"Resolving sources for '{group}:{artifact}:{version}' failed with exit "
            f"{result.returncode}. Output tail:\n{combined[-2000:]}"
        )
    jars: list[str] = parse_jar_lines(result.stdout)
    return jars[0] if jars else None


def _is_safe_java_entry(entry: str) -> bool:
    """Reject absolute or parent-escaping zip entries before writing them out."""
    if not entry.endswith(".java"):
        return False
    normalized: str = entry.replace("\\", "/")
    if normalized.startswith("/") or os.path.isabs(normalized):
        return False
    return ".." not in normalized.split("/")


def extract_sources(jar_path: str, sources_dir: str) -> int:
    """Extract the `.java` entries of a sources jar and return how many were written.

    The directory is cleared first so a re-run cannot leave stale sources from an
    earlier coordinate visible to the cover agent.
    """
    if os.path.exists(sources_dir):
        shutil.rmtree(sources_dir)
    os.makedirs(sources_dir, exist_ok=True)
    written: int = 0
    skipped: list[str] = []
    try:
        with zipfile.ZipFile(jar_path) as sources:
            for entry in sources.namelist():
                if entry.endswith("/"):
                    continue
                if not _is_safe_java_entry(entry):
                    if entry.endswith(".java"):
                        skipped.append(entry)
                    continue
                target: str = os.path.join(sources_dir, entry)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with sources.open(entry) as source_file, open(target, "wb") as target_file:
                    shutil.copyfileobj(source_file, target_file)
                written += 1
    except (OSError, zipfile.BadZipFile) as error:
        raise BaselineSourcesError(f"Cannot read sources jar '{jar_path}': {error}") from error
    if skipped:
        print(
            f"WARNING: skipped {len(skipped)} unsafe sources entries: "
            f"{', '.join(sorted(skipped)[:5])}",
            file=sys.stderr,
        )
    return written


def _split_coordinate(coordinate: str) -> tuple[str, str, str]:
    parts: list[str] = coordinate.split(":")
    if len(parts) != 3 or any(not part for part in parts):
        raise BaselineSourcesError(
            f"Coordinate must use non-empty group:artifact:version form; got '{coordinate}'."
        )
    return parts[0], parts[1], parts[2]


def prepare(repo_path: str, coordinate: str, output_dir: str, sources_dir: str) -> dict:
    """Resolve jars and sources for one coordinate and write `library.json`."""
    group: str
    artifact: str
    version: str
    group, artifact, version = _split_coordinate(coordinate)

    jar_paths: list[str] = list_library_jars(repo_path, coordinate)
    main_jars: list[str]
    jar_selection: str
    main_jars, jar_selection = select_main_jars(jar_paths, artifact, version)

    caveats: list[str] = []
    if jar_selection == "classifier-excluded":
        caveats.append(
            f"No artifact named '{artifact}-{version}.jar' resolved; every classifier-free "
            "jar on the test runtime classpath was kept as the scoring denominator."
        )

    sources_jar: str | None = resolve_sources_jar(repo_path, group, artifact, version)
    java_file_count: int = 0
    resolved_sources_dir: str | None = None
    if sources_jar:
        java_file_count = extract_sources(sources_jar, sources_dir)
        resolved_sources_dir = os.path.abspath(sources_dir)
        if java_file_count == 0:
            caveats.append(
                f"Sources jar '{os.path.basename(sources_jar)}' contains no '.java' entries; "
                "the cover agent has only the binary jar as library context."
            )
    else:
        # Not every library publishes sources; the run continues with the jar
        # alone rather than failing (§WF-code-coverage-baseline.3.1).
        caveats.append(
            f"No sources artifact was published for '{coordinate}'; the cover agent has only "
            "the binary jar as library context."
        )

    record: dict = {
        "coordinate": coordinate,
        "libraryJars": [os.path.abspath(path) for path in main_jars],
        "jarSelection": jar_selection,
        "sourcesAvailable": bool(sources_jar) and java_file_count > 0,
        "sourcesJar": os.path.abspath(sources_jar) if sources_jar else None,
        "sourcesDir": resolved_sources_dir if java_file_count else None,
        "javaFileCount": java_file_count,
        "caveats": caveats,
    }

    os.makedirs(output_dir, exist_ok=True)
    with open(os.path.join(output_dir, "library.json"), "w", encoding="utf-8") as record_file:
        json.dump(record, record_file, indent=2, sort_keys=True)
        record_file.write("\n")
    return record


def build_parser() -> argparse.ArgumentParser:
    """Return the command line parser for the baseline prepare step."""
    parser = argparse.ArgumentParser(
        description="Resolve the library jar and sources for the unguided coverage baseline.",
    )
    parser.add_argument("--repo-path", required=True, help="Issue worktree / repo root.")
    parser.add_argument("--coordinate", required=True, help="group:artifact:version.")
    parser.add_argument("--output-dir", required=True, help="Directory receiving library.json.")
    parser.add_argument(
        "--sources-dir", required=True,
        help="Directory the sources jar is extracted into (cleared on every run).",
    )
    return parser


def main() -> int:
    """Prepare one coordinate and print what the cover agent will see."""
    args = build_parser().parse_args()
    try:
        record: dict = prepare(
            repo_path=args.repo_path,
            coordinate=args.coordinate,
            output_dir=args.output_dir,
            sources_dir=args.sources_dir,
        )
    except (BaselineSourcesError, BaselineScoreError, subprocess.SubprocessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        f"[baseline-prepare] {record['coordinate']}: "
        f"{len(record['libraryJars'])} main jar(s) ({record['jarSelection']}), "
        f"{record['javaFileCount']} source files, "
        f"sources {'available' if record['sourcesAvailable'] else 'unavailable'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
