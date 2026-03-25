import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import meta_controller as mc  # noqa: E402


WORKFLOW_PATH = ".claude/skills/feature-request-v2/references/feature-workflow.yaml"
SCENARIO_PATH = ".claude/skills/feature-request-v2/references/meta-controller-scenarios.json"


class MetaControllerDecisionTests(unittest.TestCase):
    def test_phase_gate_blocks_parallel_before_phase5(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 2,
                "task_count": 10,
                "parallelizable_fraction": 0.95,
                "coupling": 0.1,
                "novelty": 0.2,
                "confidence": 0.8,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertFalse(result["execution"]["parallel_allowed"])
        self.assertEqual(result["execution"]["recommended_agents"], 1)
        self.assertEqual(result["execution"]["recommended_mode"], "single_orchestrator")

    def test_phase4_test_gate_blocks_parallel(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 4,
                "task_count": 10,
                "parallelizable_fraction": 0.95,
                "coupling": 0.1,
                "novelty": 0.2,
                "confidence": 0.8,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertFalse(result["execution"]["parallel_allowed"])
        self.assertEqual(result["execution"]["recommended_agents"], 1)
        self.assertEqual(result["execution"]["recommended_mode"], "single_orchestrator")

    def test_parallel_selected_when_parallelism_high_and_coupling_low(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "task_count": 12,
                "parallelizable_fraction": 0.92,
                "coupling": 0.12,
                "novelty": 0.25,
                "confidence": 0.78,
                "blast_radius": 0.25,
                "failure_impact": 0.4,
                "deadline_pressure": 0.9,
                "compute_budget": 0.85,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertTrue(result["execution"]["parallel_allowed"])
        self.assertGreaterEqual(result["execution"]["recommended_agents"], 2)
        self.assertEqual(
            result["execution"]["recommended_mode"], "orchestrator_plus_parallel_agents"
        )

    def test_large_task_volume_scales_parallel_recommendation(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "task_count": 40,
                "parallelizable_fraction": 0.78,
                "coupling": 0.35,
                "novelty": 0.35,
                "confidence": 0.68,
                "blast_radius": 0.35,
                "failure_impact": 0.5,
                "deadline_pressure": 0.62,
                "compute_budget": 0.75,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertGreaterEqual(result["execution"]["recommended_agents"], 3)
        self.assertEqual(
            result["execution"]["recommended_mode"], "orchestrator_plus_parallel_agents"
        )

    def test_single_selected_when_merge_and_risk_costs_dominate(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "task_count": 10,
                "parallelizable_fraction": 0.55,
                "coupling": 0.92,
                "novelty": 0.82,
                "confidence": 0.5,
                "blast_radius": 0.8,
                "failure_impact": 0.9,
                "deadline_pressure": 0.6,
                "compute_budget": 0.7,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertEqual(result["execution"]["recommended_agents"], 1)
        self.assertEqual(result["execution"]["recommended_mode"], "single_orchestrator")

    def test_instinctual_mode_requires_thresholds(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "confidence": 0.95,
                "novelty": 0.1,
                "blast_radius": 0.08,
                "failure_impact": 0.2,
                "task_count": 6,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertTrue(result["cognition"]["threshold_pass"])
        self.assertEqual(result["cognition"]["recommended_mode"], "instinctual")

    def test_chunking_respects_task_bounds(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "task_count": 7,
                "parallelizable_fraction": 0.7,
                "coupling": 0.3,
                "novelty": 0.3,
                "confidence": 0.7,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        chunk_size = result["chunking"]["recommended_chunk_size"]
        self.assertGreaterEqual(chunk_size, 1)
        self.assertLessEqual(chunk_size, state.task_count)

    def test_parallel_bonus_guardrail(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 5,
                "task_count": 9,
                "parallelizable_fraction": 0.88,
                "coupling": 0.95,
                "novelty": 0.9,
                "confidence": 0.55,
                "blast_radius": 0.85,
                "failure_impact": 0.95,
                "deadline_pressure": 0.8,
                "compute_budget": 0.8,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertFalse(result["reward_shaping"]["parallel_bonus_applied"])

    def test_phase6_review_fix_allows_parallel(self):
        state = mc.DecisionState.from_dict(
            {
                "phase": 6,
                "task_count": 8,
                "parallelizable_fraction": 0.7,
                "coupling": 0.2,
                "novelty": 0.15,
                "confidence": 0.85,
                "blast_radius": 0.2,
                "failure_impact": 0.3,
                "deadline_pressure": 0.6,
                "compute_budget": 0.8,
            }
        )
        result = mc.decide(state, workflow_config_path=WORKFLOW_PATH)
        self.assertTrue(result["execution"]["parallel_allowed"])

    def test_infer_state_from_implementation_plan_uses_task_breakdown(self):
        plan_text = """
Task Breakdown

Phase 1: Config
- Create GatewayProperties
- Add application-test.yml

Phase 2: Domain
- Create PaymentAuditRepository
- Update PaymentAuthorizationService
---
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            plan_path = Path(tmpdir) / "implementation-plan.md"
            plan_path.write_text(plan_text, encoding="utf-8")
            inferred = mc.infer_state_from_implementation_plan(plan_path)

        self.assertEqual(inferred["task_count"], 4)
        self.assertGreater(inferred["parallelizable_fraction"], 0.5)
        self.assertGreater(inferred["confidence"], 0.5)


class MetaControllerScenarioEvaluationTests(unittest.TestCase):
    def test_reference_scenarios_evaluate_without_mismatches(self):
        import evaluate_meta_controller as evaluator

        report = evaluator.evaluate_scenarios(SCENARIO_PATH, WORKFLOW_PATH)
        self.assertEqual(report["summary"]["mismatches"], 0, msg=json_safe(report))


def json_safe(payload):
    import json

    return json.dumps(payload, indent=2, sort_keys=True)


if __name__ == "__main__":
    unittest.main()
