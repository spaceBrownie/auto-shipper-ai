#!/usr/bin/env python3
"""
Scenario-driven evaluator for meta_controller.py (v2 — 6-phase).

Use this to audit policy behavior across fixed scenarios and track
recommendation drift while tuning constants.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import meta_controller as mc  # noqa: E402


DEFAULT_SCENARIOS = (
    ".claude/skills/feature-request-v2/references/meta-controller-scenarios.json"
)
DEFAULT_WORKFLOW = ".claude/skills/feature-request-v2/references/feature-workflow.yaml"


def _load_scenarios(path: str) -> List[Dict[str, Any]]:
    with open(path, "r", encoding="utf-8") as handle:
        payload = json.load(handle)
    if isinstance(payload, dict):
        payload = payload.get("scenarios", [])
    if not isinstance(payload, list):
        raise ValueError("Scenario file must be a list or object containing 'scenarios' list.")
    return payload


def _check_expectation(
    result: Dict[str, Any],
    key: str,
    expected: Any,
) -> Tuple[bool, str]:
    execution = result["execution"]
    planning = result["planning"]
    cognition = result["cognition"]
    chunking = result["chunking"]
    reward = result["reward_shaping"]

    if key == "execution_mode":
        actual = execution["recommended_mode"]
        return actual == expected, f"execution_mode expected={expected} actual={actual}"
    if key == "exact_parallel_agents":
        actual = execution["recommended_agents"]
        return actual == int(expected), f"exact_parallel_agents expected={expected} actual={actual}"
    if key == "min_parallel_agents":
        actual = execution["recommended_agents"]
        return actual >= int(expected), f"min_parallel_agents expected>={expected} actual={actual}"
    if key == "max_parallel_agents":
        actual = execution["recommended_agents"]
        return actual <= int(expected), f"max_parallel_agents expected<={expected} actual={actual}"
    if key == "planning_mode":
        actual = planning["recommended_mode"]
        return actual == expected, f"planning_mode expected={expected} actual={actual}"
    if key == "min_planning_depth":
        actual = planning["recommended_depth"]
        return actual >= int(expected), f"min_planning_depth expected>={expected} actual={actual}"
    if key == "max_planning_depth":
        actual = planning["recommended_depth"]
        return actual <= int(expected), f"max_planning_depth expected<={expected} actual={actual}"
    if key == "cognition_mode":
        actual = cognition["recommended_mode"]
        return actual == expected, f"cognition_mode expected={expected} actual={actual}"
    if key == "min_chunk_size":
        actual = chunking["recommended_chunk_size"]
        return actual >= int(expected), f"min_chunk_size expected>={expected} actual={actual}"
    if key == "max_chunk_size":
        actual = chunking["recommended_chunk_size"]
        return actual <= int(expected), f"max_chunk_size expected<={expected} actual={actual}"
    if key == "parallel_bonus_applied":
        actual = bool(reward["parallel_bonus_applied"])
        return actual == bool(expected), f"parallel_bonus_applied expected={expected} actual={actual}"
    if key == "parallel_preference_enabled":
        actual = bool(reward["parallel_preference_enabled"])
        return actual == bool(expected), f"parallel_preference_enabled expected={expected} actual={actual}"

    return False, f"Unknown expectation key: {key}"


def evaluate_scenarios(
    scenarios_path: str,
    workflow_config_path: str,
) -> Dict[str, Any]:
    scenarios = _load_scenarios(scenarios_path)
    evaluated: List[Dict[str, Any]] = []

    total_expectations = 0
    passed_expectations = 0
    mismatches = 0

    execution_counts = {
        "single_orchestrator": 0,
        "orchestrator_plus_parallel_agents": 0,
    }
    cognition_counts = {"instinctual": 0, "deliberative": 0}
    score_margins: List[float] = []
    planning_depths: List[int] = []
    chunk_sizes: List[int] = []

    for scenario in scenarios:
        name = scenario.get("name", "unnamed")
        state = mc.DecisionState.from_dict(scenario.get("state", {}))
        tuning = mc.ControllerTuning.from_dict(scenario.get("tuning"))
        result = mc.decide(state, workflow_config_path=workflow_config_path, tuning=tuning)

        execution_counts[result["execution"]["recommended_mode"]] += 1
        cognition_counts[result["cognition"]["recommended_mode"]] += 1
        score_margins.append(float(result["execution"]["score_margin_vs_single"]))
        planning_depths.append(int(result["planning"]["recommended_depth"]))
        chunk_sizes.append(int(result["chunking"]["recommended_chunk_size"]))

        checks: List[Dict[str, Any]] = []
        scenario_pass = True
        expected = scenario.get("expected", {})

        for key, value in expected.items():
            ok, detail = _check_expectation(result, key, value)
            checks.append({"key": key, "passed": ok, "detail": detail})
            total_expectations += 1
            if ok:
                passed_expectations += 1
            else:
                scenario_pass = False
                mismatches += 1

        evaluated.append(
            {
                "name": name,
                "passed": scenario_pass,
                "checks": checks,
                "result": {
                    "execution_mode": result["execution"]["recommended_mode"],
                    "recommended_agents": result["execution"]["recommended_agents"],
                    "planning_mode": result["planning"]["recommended_mode"],
                    "planning_depth": result["planning"]["recommended_depth"],
                    "cognition_mode": result["cognition"]["recommended_mode"],
                    "chunk_size": result["chunking"]["recommended_chunk_size"],
                    "chunk_count": result["chunking"]["recommended_chunk_count"],
                    "parallel_bonus_applied": result["reward_shaping"]["parallel_bonus_applied"],
                    "parallel_preference_enabled": result["reward_shaping"]["parallel_preference_enabled"],
                    "score_margin_vs_single": result["execution"]["score_margin_vs_single"],
                },
            }
        )

    scenario_count = len(scenarios)
    passed_scenarios = sum(1 for row in evaluated if row["passed"])
    failed_scenarios = scenario_count - passed_scenarios

    report = {
        "summary": {
            "scenario_count": scenario_count,
            "passed_scenarios": passed_scenarios,
            "failed_scenarios": failed_scenarios,
            "total_expectations": total_expectations,
            "passed_expectations": passed_expectations,
            "expectation_accuracy": round((passed_expectations / total_expectations), 4)
            if total_expectations
            else 1.0,
            "mismatches": mismatches,
            "avg_score_margin_vs_single": round(sum(score_margins) / len(score_margins), 4)
            if score_margins
            else 0.0,
            "avg_planning_depth": round(sum(planning_depths) / len(planning_depths), 4)
            if planning_depths
            else 0.0,
            "avg_chunk_size": round(sum(chunk_sizes) / len(chunk_sizes), 4)
            if chunk_sizes
            else 0.0,
        },
        "distribution": {
            "execution_mode": execution_counts,
            "cognition_mode": cognition_counts,
        },
        "scenarios": evaluated,
    }
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate meta-controller with scenario fixtures (v2).")
    parser.add_argument(
        "--scenarios",
        type=str,
        default=DEFAULT_SCENARIOS,
        help="Path to scenario JSON file.",
    )
    parser.add_argument(
        "--workflow-config",
        type=str,
        default=DEFAULT_WORKFLOW,
        help="Workflow config for phase gating.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output full JSON report.",
    )
    parser.add_argument(
        "--fail-on-mismatch",
        action="store_true",
        help="Exit non-zero if any expectation mismatches are found.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = evaluate_scenarios(args.scenarios, args.workflow_config)

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        summary = report["summary"]
        print("Meta-Controller Evaluation (v2)")
        print("=" * 32)
        print(f"Scenarios: {summary['scenario_count']}")
        print(f"Passed: {summary['passed_scenarios']}")
        print(f"Failed: {summary['failed_scenarios']}")
        print(f"Expectation Accuracy: {summary['expectation_accuracy']:.2%}")
        print(f"Mismatches: {summary['mismatches']}")
        print(f"Avg Score Margin vs Single: {summary['avg_score_margin_vs_single']}")
        print(f"Avg Planning Depth: {summary['avg_planning_depth']}")
        print(f"Avg Chunk Size: {summary['avg_chunk_size']}")
        print("\nExecution Distribution:")
        for mode, count in report["distribution"]["execution_mode"].items():
            print(f"- {mode}: {count}")
        print("Cognition Distribution:")
        for mode, count in report["distribution"]["cognition_mode"].items():
            print(f"- {mode}: {count}")

        failures = [row for row in report["scenarios"] if not row["passed"]]
        if failures:
            print("\nMismatches:")
            for failure in failures:
                print(f"- {failure['name']}")
                for check in failure["checks"]:
                    if not check["passed"]:
                        print(f"  * {check['detail']}")

    if args.fail_on_mismatch and report["summary"]["mismatches"] > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
