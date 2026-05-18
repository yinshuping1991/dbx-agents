import json
import tempfile
import textwrap
import unittest
from pathlib import Path

import validate_agents


class ValidateAgentsTest(unittest.TestCase):
    def test_versions_must_match_included_agent_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "settings.gradle").write_text(
                "include 'common', 'test-support', 'h2', 'oracle'\n",
                encoding="utf-8",
            )
            (root / "versions.json").write_text(
                json.dumps({"h2": "0.1.0", "mongodb": "0.1.0"}),
                encoding="utf-8",
            )

            problems = validate_agents.validate_versions(root)

            self.assertEqual(
                [
                    "included module missing version: oracle",
                    "versions key not included: mongodb",
                ],
                problems,
            )

    def test_source_scan_rejects_old_execute_query_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "h2/src/main/java/com/dbx/agent/h2/H2Agent.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                textwrap.dedent(
                    """
                    private val QUERY_PREFIXES = listOf("SELECT")
                    fun executeQuery(sql: String) {
                        val trimmedSql = sql.trim()
                        stmt.executeUpdate(trimmedSql)
                    }
                    """
                ),
                encoding="utf-8",
            )

            problems = validate_agents.validate_source_patterns(root)

            self.assertEqual(
                [
                    "h2/src/main/java/com/dbx/agent/h2/H2Agent.java:2: forbidden local SQL prefix classifier",
                    "h2/src/main/java/com/dbx/agent/h2/H2Agent.java:5: forbidden executeUpdate(trimmedSql) in query execution",
                ],
                problems,
            )

    def test_manifest_validation_requires_registry_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = root / "h2"
            module.mkdir()
            (root / "build.gradle").write_text(
                textwrap.dedent(
                    """
                    configure(agentProjects) {
                        tasks.named('shadowJar') {
                            archiveBaseName = "dbx-agent-${project.name}"
                        }
                    }
                    """
                ),
                encoding="utf-8",
            )
            (module / "build.gradle").write_text(
                textwrap.dedent(
                    """
                    tasks.named('shadowJar') {
                        manifest {
                            attributes(
                                'Agent-Label': 'H2'
                            )
                        }
                    }
                    """
                ),
                encoding="utf-8",
            )

            problems = validate_agents.validate_manifest_fields(root, {"h2"})

            self.assertEqual(
                ["h2/build.gradle: missing Main-Class manifest attribute"],
                problems,
            )

    def test_kotlin_residue_scan_rejects_kt_and_kts_files_outside_build_dirs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            forbidden_source = root / "h2/src/main/kotlin/com/dbx/agent/h2/H2Agent.kt"
            forbidden_build = root / "settings.gradle.kts"
            ignored_build_output = root / "h2/build/tmp/Generated.kt"
            ignored_gradle_cache = root / ".gradle/caches/init.gradle.kts"
            for path in (
                forbidden_source,
                forbidden_build,
                ignored_build_output,
                ignored_gradle_cache,
            ):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("", encoding="utf-8")

            problems = validate_agents.validate_no_kotlin_residue(root)

            self.assertEqual(
                [
                    "h2/src/main/kotlin/com/dbx/agent/h2/H2Agent.kt: forbidden Kotlin file",
                    "settings.gradle.kts: forbidden Kotlin file",
                ],
                problems,
            )


if __name__ == "__main__":
    unittest.main()
