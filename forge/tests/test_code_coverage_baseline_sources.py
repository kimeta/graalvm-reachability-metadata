# Copyright and related rights waived via CC0
#
# You should have received a copy of the CC0 legalcode along with this
# work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.

import json
import os
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from utility_scripts import code_coverage_baseline_sources as module

COORDINATE = "com.example:foo:1.0"

EXPECTED_FIELDS = {
    "caveats",
    "coordinate",
    "javaFileCount",
    "jarSelection",
    "libraryJars",
    "sourcesAvailable",
    "sourcesDir",
    "sourcesJar",
}


def _write_jar(path: str, entries: dict[str, str]) -> str:
    with zipfile.ZipFile(path, "w") as jar:
        for name, content in entries.items():
            jar.writestr(name, content)
    return path


class ParseJarLinesTest(unittest.TestCase):

    def test_keeps_only_existing_jar_paths(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            jar: str = _write_jar(os.path.join(work_dir, "foo-1.0.jar"), {"a/B.class": ""})
            output: str = "\n".join([
                "> Task :listLibraryJars",
                "Welcome to Gradle 8.10!",
                f"  {jar}  ",
                "/does/not/exist/ghost-1.0.jar",
                os.path.join(work_dir, "notes.txt"),
                jar,
                "BUILD SUCCESSFUL in 3s",
            ])
            self.assertEqual([jar], module.parse_jar_lines(output))

    def test_empty_output_yields_no_jars(self) -> None:
        self.assertEqual([], module.parse_jar_lines("\n\n> Task :listLibraryJars\n"))


class SelectMainJarsTest(unittest.TestCase):

    def test_test_classifier_jar_is_excluded(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            main_jar: str = _write_jar(
                os.path.join(work_dir, "foo-1.0.jar"), {"com/example/Foo.class": ""}
            )
            test_jar: str = _write_jar(
                os.path.join(work_dir, "foo-1.0-test.jar"), {"com/example/FooTest.class": ""}
            )
            selected, selection = module.select_main_jars([test_jar, main_jar], "foo", "1.0")
            self.assertEqual([main_jar], selected)
            self.assertEqual("exact-name", selection)


class ExtractSourcesTest(unittest.TestCase):

    def test_extracts_only_java_files(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            jar: str = _write_jar(os.path.join(work_dir, "foo-1.0-sources.jar"), {
                "com/example/Foo.java": "class Foo {}",
                "com/example/Bar.java": "class Bar {}",
                "META-INF/MANIFEST.MF": "Manifest-Version: 1.0",
                "com/example/Foo.class": "",
            })
            sources_dir: str = os.path.join(work_dir, "sources")
            self.assertEqual(2, module.extract_sources(jar, sources_dir))
            extracted: list[str] = sorted(
                os.path.relpath(os.path.join(root, name), sources_dir)
                for root, _, names in os.walk(sources_dir) for name in names
            )
            self.assertEqual(
                [os.path.join("com", "example", "Bar.java"),
                 os.path.join("com", "example", "Foo.java")],
                extracted,
            )

    def test_traversal_entries_are_refused(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            jar: str = _write_jar(os.path.join(work_dir, "evil-sources.jar"), {
                "com/example/Foo.java": "class Foo {}",
                "../evil.java": "class Evil {}",
                "/tmp/absolute.java": "class Absolute {}",
            })
            sources_dir: str = os.path.join(work_dir, "sources")
            self.assertEqual(1, module.extract_sources(jar, sources_dir))
            self.assertFalse(os.path.exists(os.path.join(work_dir, "evil.java")))
            self.assertTrue(
                os.path.isfile(os.path.join(sources_dir, "com", "example", "Foo.java"))
            )

    def test_existing_sources_directory_is_cleared(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            jar: str = _write_jar(
                os.path.join(work_dir, "foo-sources.jar"), {"com/example/Foo.java": "class Foo {}"}
            )
            sources_dir: str = os.path.join(work_dir, "sources")
            os.makedirs(os.path.join(sources_dir, "stale"))
            stale_file: str = os.path.join(sources_dir, "stale", "Old.java")
            with open(stale_file, "w", encoding="utf-8") as handle:
                handle.write("class Old {}")
            self.assertEqual(1, module.extract_sources(jar, sources_dir))
            self.assertFalse(os.path.exists(stale_file))


class ResolveSourcesJarTest(unittest.TestCase):

    def _repo_with_wrapper(self, work_dir: str) -> str:
        repo_path: str = os.path.join(work_dir, "repo")
        os.makedirs(repo_path)
        wrapper: str = os.path.join(repo_path, "gradlew")
        with open(wrapper, "w", encoding="utf-8") as handle:
            handle.write("#!/bin/sh\nexit 0\n")
        return repo_path

    def test_missing_wrapper_is_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            with self.assertRaises(module.BaselineSourcesError):
                module.resolve_sources_jar(work_dir, "com.example", "foo", "1.0")

    def test_missing_artifact_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            repo_path: str = self._repo_with_wrapper(work_dir)
            failure = type("Result", (), {
                "returncode": 1,
                "stdout": "",
                "stderr": "Could not find foo-1.0-sources.jar (com.example:foo:1.0).",
            })()
            with patch.object(module.subprocess, "run", return_value=failure):
                self.assertIsNone(
                    module.resolve_sources_jar(repo_path, "com.example", "foo", "1.0")
                )

    def test_other_gradle_failure_is_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            repo_path: str = self._repo_with_wrapper(work_dir)
            failure = type("Result", (), {
                "returncode": 1,
                "stdout": "",
                "stderr": "FAILURE: Build failed: Unsupported class file major version.",
            })()
            with patch.object(module.subprocess, "run", return_value=failure):
                with self.assertRaises(module.BaselineSourcesError):
                    module.resolve_sources_jar(repo_path, "com.example", "foo", "1.0")

    def test_resolved_path_is_returned(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            repo_path: str = self._repo_with_wrapper(work_dir)
            sources_jar: str = _write_jar(
                os.path.join(work_dir, "foo-1.0-sources.jar"), {"com/example/Foo.java": "class Foo {}"}
            )
            success = type("Result", (), {
                "returncode": 0, "stdout": f"noise\n{sources_jar}\n", "stderr": "",
            })()
            with patch.object(module.subprocess, "run", return_value=success):
                self.assertEqual(
                    sources_jar,
                    module.resolve_sources_jar(repo_path, "com.example", "foo", "1.0"),
                )


class PrepareTest(unittest.TestCase):

    def _jars(self, work_dir: str) -> tuple[str, str]:
        main_jar: str = _write_jar(
            os.path.join(work_dir, "foo-1.0.jar"), {"com/example/Foo.class": ""}
        )
        test_jar: str = _write_jar(
            os.path.join(work_dir, "foo-1.0-test.jar"), {"com/example/FooTest.class": ""}
        )
        return main_jar, test_jar

    def test_record_has_exactly_the_documented_fields(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            main_jar, test_jar = self._jars(work_dir)
            sources_jar: str = _write_jar(os.path.join(work_dir, "foo-1.0-sources.jar"), {
                "com/example/Foo.java": "class Foo {}",
            })
            output_dir: str = os.path.join(work_dir, "prepare")
            sources_dir: str = os.path.join(work_dir, "sources")
            with patch.object(module, "list_library_jars", return_value=[main_jar, test_jar]), \
                    patch.object(module, "resolve_sources_jar", return_value=sources_jar):
                record: dict = module.prepare(work_dir, COORDINATE, output_dir, sources_dir)

            with open(os.path.join(output_dir, "library.json"), "r", encoding="utf-8") as handle:
                written: dict = json.load(handle)
            self.assertEqual(EXPECTED_FIELDS, set(record))
            self.assertEqual(record, written)
            self.assertEqual([main_jar], record["libraryJars"])
            self.assertEqual("exact-name", record["jarSelection"])
            self.assertTrue(record["sourcesAvailable"])
            self.assertEqual(os.path.abspath(sources_dir), record["sourcesDir"])
            self.assertEqual(1, record["javaFileCount"])
            self.assertEqual([], record["caveats"])

    def test_missing_sources_artifact_is_a_caveat_not_a_failure(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            main_jar, test_jar = self._jars(work_dir)
            output_dir: str = os.path.join(work_dir, "prepare")
            sources_dir: str = os.path.join(work_dir, "sources")
            argv: list[str] = [
                "code_coverage_baseline_sources.py",
                "--repo-path", work_dir,
                "--coordinate", COORDINATE,
                "--output-dir", output_dir,
                "--sources-dir", sources_dir,
            ]
            with patch.object(module, "list_library_jars", return_value=[main_jar, test_jar]), \
                    patch.object(module, "resolve_sources_jar", return_value=None), \
                    patch.object(module.sys, "argv", argv):
                self.assertEqual(0, module.main())

            with open(os.path.join(output_dir, "library.json"), "r", encoding="utf-8") as handle:
                record: dict = json.load(handle)
            self.assertEqual(EXPECTED_FIELDS, set(record))
            self.assertFalse(record["sourcesAvailable"])
            self.assertIsNone(record["sourcesJar"])
            self.assertIsNone(record["sourcesDir"])
            self.assertEqual(0, record["javaFileCount"])
            self.assertEqual(1, len(record["caveats"]))
            self.assertIn("only", record["caveats"][0])

    def test_invalid_coordinate_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as work_dir:
            argv: list[str] = [
                "code_coverage_baseline_sources.py",
                "--repo-path", work_dir,
                "--coordinate", "com.example:foo",
                "--output-dir", os.path.join(work_dir, "prepare"),
                "--sources-dir", os.path.join(work_dir, "sources"),
            ]
            with patch.object(module.sys, "argv", argv):
                self.assertEqual(1, module.main())


if __name__ == "__main__":
    unittest.main()
