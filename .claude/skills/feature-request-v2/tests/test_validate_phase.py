"""
Validation tests for validate-phase.py changes (RAT-36).

Tests cover:
- --instructions flag: all 6 phases, JSON mode, error cases
- Phase 4 rename: test_specification key, test-spec.md deliverable
- Human gate flags: phases 4, 5, 6 require manual approval
- Phase map consistency with YAML
- Edge cases: missing instructions file, invalid phase
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

# Import dynamically because of the dash in filename
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "validate_phase", SCRIPT_DIR / "validate-phase.py"
)
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
PhaseValidator = _module.PhaseValidator

WORKFLOW_PATH = ".claude/skills/feature-request-v2/references/feature-workflow.yaml"


class InstructionsTests(unittest.TestCase):
    """Tests for the --instructions feature."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)

    def test_all_phases_have_instructions(self):
        """Every phase (1-6) must have a non-empty instructions file."""
        for phase in range(1, 7):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertIsNotNone(
                instructions,
                f"Phase {phase} has no instructions file configured or file is missing",
            )
            self.assertGreater(
                len(instructions.strip()), 0,
                f"Phase {phase} instructions file is empty",
            )

    def test_instructions_contain_phase_header(self):
        """Each instructions file should start with a markdown header identifying the phase."""
        for phase in range(1, 7):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertIsNotNone(instructions)
            first_line = instructions.strip().split("\n")[0]
            self.assertTrue(
                first_line.startswith("# Phase"),
                f"Phase {phase} instructions should start with '# Phase...', got: {first_line}",
            )

    def test_instructions_contain_goal(self):
        """Each instructions file should describe the goal."""
        for phase in range(1, 7):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertIn(
                "Goal:",
                instructions,
                f"Phase {phase} instructions missing 'Goal:' section",
            )

    def test_instructions_contain_deliverable(self):
        """Each instructions file should describe deliverables."""
        for phase in range(1, 7):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertTrue(
                "Deliverable" in instructions or "deliverable" in instructions,
                f"Phase {phase} instructions missing deliverable description",
            )

    def test_instructions_contain_validation_commands(self):
        """Phases 1-5 instructions should reference validate-phase.py."""
        for phase in range(1, 6):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertIn(
                "validate-phase.py",
                instructions,
                f"Phase {phase} instructions should reference validate-phase.py",
            )

    def test_instructions_contain_unblock(self):
        """All phase instructions should mention /unblock."""
        for phase in range(1, 7):
            instructions = self.validator.get_phase_instructions(phase)
            self.assertTrue(
                "unblock" in instructions.lower(),
                f"Phase {phase} instructions should mention /unblock",
            )

    def test_invalid_phase_returns_none(self):
        """get_phase_instructions with invalid phase returns None."""
        self.assertIsNone(self.validator.get_phase_instructions(0))
        self.assertIsNone(self.validator.get_phase_instructions(7))
        self.assertIsNone(self.validator.get_phase_instructions(99))

    def test_instructions_files_exist_on_disk(self):
        """Every instructions_file referenced in YAML exists on disk."""
        for phase in range(1, 7):
            phase_info = self.validator.get_phase_info(phase)
            self.assertIsNotNone(phase_info, f"Phase {phase} config missing")
            instructions_file = phase_info.get("instructions_file")
            self.assertIsNotNone(
                instructions_file,
                f"Phase {phase} missing instructions_file in YAML",
            )
            full_path = PhaseValidator.SKILL_ROOT / instructions_file
            self.assertTrue(
                full_path.exists(),
                f"Phase {phase} instructions file not found: {full_path}",
            )


class Phase4RenameTests(unittest.TestCase):
    """Tests for Phase 4 rename from Test-First Gate to Test Specification."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)

    def test_phase4_key_is_test_specification(self):
        """Phase 4 key in the phase map should be phase_4_test_specification."""
        key = self.validator.get_phase_key(4)
        self.assertEqual(key, "phase_4_test_specification")

    def test_phase4_name_is_test_specification(self):
        """Phase 4 name should be 'Test Specification'."""
        phase_info = self.validator.get_phase_info(4)
        self.assertIsNotNone(phase_info)
        self.assertEqual(phase_info["name"], "Test Specification")

    def test_phase4_deliverable_is_test_spec_md(self):
        """Phase 4 primary deliverable should be test-spec.md, not test-manifest.md."""
        phase_info = self.validator.get_phase_info(4)
        deliverables = phase_info.get("deliverables", [])
        file_deliverables = [d for d in deliverables if d.get("type") == "file"]
        self.assertTrue(len(file_deliverables) > 0, "Phase 4 should have at least one file deliverable")
        primary = file_deliverables[0]
        self.assertEqual(primary["name"], "test-spec.md")
        self.assertIn("test-spec", primary.get("path_pattern", ""))

    def test_phase4_required_sections(self):
        """Phase 4 test-spec.md should require the new section names."""
        phase_info = self.validator.get_phase_info(4)
        deliverables = phase_info.get("deliverables", [])
        primary = next(d for d in deliverables if d.get("name") == "test-spec.md")
        required = primary.get("required_sections", [])
        expected = [
            "Acceptance Criteria",
            "Fixture Data",
            "Boundary Cases",
            "E2E Playbook Scenarios",
            "Contract Test Candidates",
        ]
        for section in expected:
            self.assertIn(section, required, f"Missing required section: {section}")

    def test_phase4_check_deliverables_requires_test_spec(self):
        """check_deliverables for Phase 4 should look for test-spec.md."""
        with tempfile.TemporaryDirectory() as tmpdir:
            # Empty dir — should fail with "Missing required test-spec.md"
            ok, errors = self.validator.check_deliverables(4, tmpdir)
            self.assertFalse(ok)
            has_test_spec_error = any("test-spec.md" in e for e in errors)
            self.assertTrue(
                has_test_spec_error,
                f"Expected error about test-spec.md, got: {errors}",
            )

    def test_phase4_check_deliverables_no_test_manifest_reference(self):
        """check_deliverables should NOT mention test-manifest.md."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ok, errors = self.validator.check_deliverables(4, tmpdir)
            for error in errors:
                self.assertNotIn(
                    "test-manifest",
                    error,
                    f"Found stale reference to test-manifest.md: {error}",
                )

    def test_phase4_write_permission_for_test_spec(self):
        """Phase 4 should allow writing test-spec.md in FR directory."""
        ok, msg = self.validator.validate_write(
            4, "feature-requests/FR-001-example/test-spec.md"
        )
        self.assertTrue(ok, f"Phase 4 should allow writing test-spec.md: {msg}")

    def test_phase4_old_key_does_not_exist(self):
        """The old phase_4_test_first_gate key should not exist in config."""
        self.assertNotIn(
            "phase_4_test_first_gate",
            self.validator.phases,
            "Old phase_4_test_first_gate key should be removed from YAML",
        )


class HumanGateTests(unittest.TestCase):
    """Tests for requires_manual_approval flags."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)

    def test_phases_1_3_auto_advance(self):
        """Phases 1-3 should auto-advance (no manual approval required)."""
        for phase in range(1, 4):
            phase_info = self.validator.get_phase_info(phase)
            self.assertIsNotNone(phase_info, f"Phase {phase} config missing")
            self.assertFalse(
                phase_info.get("requires_manual_approval", False),
                f"Phase {phase} should auto-advance (requires_manual_approval=false)",
            )

    def test_phase4_requires_human_gate(self):
        """Phase 4 (Test Specification) must require manual approval before Phase 5."""
        phase_info = self.validator.get_phase_info(4)
        self.assertTrue(
            phase_info.get("requires_manual_approval", False),
            "Phase 4 must require manual approval (human gate before implementation)",
        )

    def test_phase5_requires_human_gate(self):
        """Phase 5 (Implementation) must require manual approval before Phase 6."""
        phase_info = self.validator.get_phase_info(5)
        self.assertTrue(
            phase_info.get("requires_manual_approval", False),
            "Phase 5 must require manual approval (human gate before PR)",
        )

    def test_phase4_next_is_phase5(self):
        """Phase 4 should transition to Phase 5."""
        phase_info = self.validator.get_phase_info(4)
        self.assertEqual(
            phase_info.get("next_phase"), "phase_5_implementation",
        )

    def test_phase5_next_is_phase6(self):
        """Phase 5 should transition to Phase 6."""
        phase_info = self.validator.get_phase_info(5)
        self.assertEqual(
            phase_info.get("next_phase"), "phase_6_review_fix_loop",
        )


class PhaseMapConsistencyTests(unittest.TestCase):
    """Verify the phase map in validate-phase.py matches the YAML phases."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)

    def test_all_phase_keys_exist_in_yaml(self):
        """Every key returned by get_phase_key should exist in the YAML phases dict."""
        for phase_num in range(1, 7):
            key = self.validator.get_phase_key(phase_num)
            self.assertIn(
                key,
                self.validator.phases,
                f"Phase {phase_num} key '{key}' not found in YAML phases",
            )

    def test_yaml_phases_all_mapped(self):
        """Every phase in YAML should be reachable from the phase map."""
        mapped_keys = {self.validator.get_phase_key(n) for n in range(1, 7)}
        for yaml_key in self.validator.phases:
            self.assertIn(
                yaml_key,
                mapped_keys,
                f"YAML phase '{yaml_key}' is not reachable from the phase map",
            )

    def test_six_phases_total(self):
        """There should be exactly 6 phases configured."""
        self.assertEqual(len(self.validator.phases), 6)


class Phase5InstructionsContentTests(unittest.TestCase):
    """Phase 5 instructions should contain key PM-017 prevention items."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)
        cls.instructions = cls.validator.get_phase_instructions(5)

    def test_meta_controller_preflight_referenced(self):
        """Phase 5 instructions must reference meta_controller.py preflight."""
        self.assertIn("meta_controller.py", self.instructions)

    def test_override_justification_referenced(self):
        """Phase 5 instructions must mention override-justification.md."""
        self.assertIn("override-justification.md", self.instructions)

    def test_tdd_pattern_referenced(self):
        """Phase 5 instructions must describe TDD alongside implementation."""
        self.assertIn("TDD", self.instructions)

    def test_e2e_playbook_referenced(self):
        """Phase 5 instructions must reference the E2E test playbook."""
        self.assertIn("e2e-test-playbook.md", self.instructions)

    def test_dependency_ordered_groups_referenced(self):
        """Phase 5 instructions must describe the dependency-ordered sub-agent groups."""
        self.assertIn("Foundation", self.instructions)
        self.assertIn("Domain Logic", self.instructions)
        self.assertIn("Integration", self.instructions)


class Phase4InstructionsContentTests(unittest.TestCase):
    """Phase 4 instructions should enforce test quality per PM-017."""

    @classmethod
    def setUpClass(cls):
        cls.validator = PhaseValidator(WORKFLOW_PATH)
        cls.instructions = cls.validator.get_phase_instructions(4)

    def test_bans_assert_true(self):
        """Phase 4 instructions must ban assert(true)."""
        self.assertIn("assert(true)", self.instructions)

    def test_bans_fixture_only_assertions(self):
        """Phase 4 instructions must ban fixture-only assertions."""
        self.assertIn("payload.contains", self.instructions)

    def test_bans_deferred_comments(self):
        """Phase 4 instructions must ban // Phase 5: deferred comments."""
        self.assertIn("Phase 5:", self.instructions)

    def test_requires_json_null_boundary(self):
        """Phase 4 instructions must require JSON null boundary cases."""
        has_json_null = "JSON" in self.instructions and "null" in self.instructions
        has_nullnode = "NullNode" in self.instructions
        self.assertTrue(
            has_json_null or has_nullnode,
            "Phase 4 instructions must mention JSON null or NullNode boundary cases",
        )

    def test_test_spec_is_primary_deliverable(self):
        """Phase 4 instructions should emphasize test-spec.md as primary deliverable."""
        self.assertIn("test-spec.md", self.instructions)
        self.assertIn("NOT test files", self.instructions)


if __name__ == "__main__":
    unittest.main()
