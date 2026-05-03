# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

from dataclasses import dataclass
import os
import subprocess


SCAFFOLD_PLACEHOLDER_TEXT = "This is just a placeholder, implement your test"
TEST_SOURCE_EXTENSIONS = (".java", ".kt", ".scala")


@dataclass(frozen=True)
class PlaceholderOccurrence:
    file_path: str
    line_number: int


@dataclass(frozen=True)
class ScaffoldPlaceholderCleanupResult:
    removed_files: list[str]
    remaining_placeholders: list[PlaceholderOccurrence]


def cleanup_scaffold_placeholder_tests(
        test_source_root: str,
        repo_path: str,
        scaffold_commit_hash: str,
) -> ScaffoldPlaceholderCleanupResult:
    """Remove placeholder test files that are unchanged since the scaffold commit."""
    if not os.path.isdir(test_source_root):
        return ScaffoldPlaceholderCleanupResult([], [])

    scaffold_test_files = _find_scaffold_test_files(test_source_root, repo_path, scaffold_commit_hash)
    placeholder_files = [
        file_path for file_path in scaffold_test_files
        if _file_contains_placeholder(file_path)
    ]
    scaffold_files = [
        file_path for file_path in placeholder_files
        if _is_unchanged_since_commit(file_path, repo_path, scaffold_commit_hash)
    ]

    removed_files: list[str] = []
    for file_path in scaffold_files:
        os.remove(file_path)
        removed_files.append(file_path)

    remaining_placeholders: list[PlaceholderOccurrence] = []
    removed_file_set = set(removed_files)
    for file_path in placeholder_files:
        if file_path in removed_file_set:
            continue
        remaining_placeholders.extend(_placeholder_occurrences(file_path))

    return ScaffoldPlaceholderCleanupResult(removed_files, remaining_placeholders)


def format_placeholder_occurrence(occurrence: PlaceholderOccurrence, repo_path: str | None = None) -> str:
    display_path = occurrence.file_path
    if repo_path:
        display_path = os.path.relpath(occurrence.file_path, repo_path)
    return f"{display_path}:{occurrence.line_number}"


def _find_scaffold_test_files(test_source_root: str, repo_path: str, scaffold_commit_hash: str) -> list[str]:
    relative_source_root = os.path.relpath(test_source_root, repo_path)
    result = subprocess.run(
        [
            "git",
            "diff-tree",
            "--root",
            "--no-commit-id",
            "--name-only",
            "--diff-filter=A",
            "-r",
            scaffold_commit_hash,
            "--",
            relative_source_root,
        ],
        cwd=repo_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return []
    return [
        os.path.join(repo_path, relative_path)
        for relative_path in result.stdout.splitlines()
        if relative_path.endswith(TEST_SOURCE_EXTENSIONS)
    ]


def _file_contains_placeholder(file_path: str) -> bool:
    try:
        with open(file_path, "r", encoding="utf-8") as source_file:
            return SCAFFOLD_PLACEHOLDER_TEXT in source_file.read()
    except OSError:
        return False


def _placeholder_occurrences(file_path: str) -> list[PlaceholderOccurrence]:
    occurrences: list[PlaceholderOccurrence] = []
    try:
        with open(file_path, "r", encoding="utf-8") as source_file:
            for line_number, line in enumerate(source_file, start=1):
                if SCAFFOLD_PLACEHOLDER_TEXT in line:
                    occurrences.append(PlaceholderOccurrence(file_path, line_number))
    except OSError:
        return []
    return occurrences


def _is_unchanged_since_commit(file_path: str, repo_path: str, commit_hash: str) -> bool:
    relative_path = os.path.relpath(file_path, repo_path)
    result = subprocess.run(
        ["git", "diff", "--quiet", commit_hash, "--", relative_path],
        cwd=repo_path,
        check=False,
    )
    return result.returncode == 0
