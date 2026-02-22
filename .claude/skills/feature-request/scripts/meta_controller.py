#!/usr/bin/env python3
"""
Brain-inspired meta-controller for feature workflow strategy selection.

This script recommends:
1) Single orchestrator vs orchestrator + parallel agents
2) Fast execution vs decomposition depth
3) Instinctual vs deliberative cognition mode
4) Task chunk size for batching
5) Reward shaping policy for parallel preference

It is phase-aware and can use validate-phase.py as a hard gate so that
recommendations stay aligned with the workflow permissions model.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import re
from dataclasses import asdict, dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, List, Optional


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def round_float(value: float, places: int = 4) -> float:
    return float(round(value, places))


@dataclass
class DecisionState:
    """Normalized control inputs for the meta-controller."""

    phase: int = 4
    task_count: int = 8
    base_duration: float = 1.0
    parallelizable_fraction: float = 0.6
    coupling: float = 0.4
    novelty: float = 0.4
    confidence: float = 0.6
    blast_radius: float = 0.4
    failure_impact: float = 0.5
    deadline_pressure: float = 0.5
    compute_budget: float = 0.7

    @classmethod
    def from_dict(cls, payload: Dict[str, Any]) -> "DecisionState":
        state = cls(**payload)
        state.phase = int(state.phase)
        state.task_count = max(1, int(state.task_count))
        state.base_duration = max(0.1, float(state.base_duration))
        state.parallelizable_fraction = clamp(float(state.parallelizable_fraction))
        state.coupling = clamp(float(state.coupling))
        state.novelty = clamp(float(state.novelty))
        state.confidence = clamp(float(state.confidence))
        state.blast_radius = clamp(float(state.blast_radius))
        state.failure_impact = clamp(float(state.failure_impact))
        state.deadline_pressure = clamp(float(state.deadline_pressure))
        state.compute_budget = clamp(float(state.compute_budget))
        return state


@dataclass
class ControllerTuning:
    """Small set of tunable policy constants for iterative calibration."""

    max_parallel_agents: int = 6
    parallel_margin_min: float = 0.012
    parallel_reward_beta: float = 0.06
    quality_floor_for_parallel_bonus: float = 0.68
    rework_ceiling_for_parallel_bonus: float = 0.45
    instinct_confidence_min: float = 0.78
    instinct_novelty_max: float = 0.32
    instinct_blast_radius_max: float = 0.25

    @classmethod
    def from_dict(cls, payload: Optional[Dict[str, Any]]) -> "ControllerTuning":
        if not payload:
            return cls()
        tuning = cls(**payload)
        tuning.max_parallel_agents = max(1, int(tuning.max_parallel_agents))
        tuning.parallel_margin_min = max(0.0, float(tuning.parallel_margin_min))
        tuning.parallel_reward_beta = max(0.0, float(tuning.parallel_reward_beta))
        tuning.quality_floor_for_parallel_bonus = clamp(float(tuning.quality_floor_for_parallel_bonus))
        tuning.rework_ceiling_for_parallel_bonus = clamp(float(tuning.rework_ceiling_for_parallel_bonus))
        tuning.instinct_confidence_min = clamp(float(tuning.instinct_confidence_min))
        tuning.instinct_novelty_max = clamp(float(tuning.instinct_novelty_max))
        tuning.instinct_blast_radius_max = clamp(float(tuning.instinct_blast_radius_max))
        return tuning


@lru_cache(maxsize=8)
def _load_phase_validator(workflow_config_path: str) -> Optional[Any]:
    """
    Dynamically load PhaseValidator from validate-phase.py.

    validate-phase.py has a dash in filename, so it is loaded via importlib.
    """
    script_path = Path(__file__).with_name("validate-phase.py")
    if not script_path.exists():
        return None

    try:
        spec = importlib.util.spec_from_file_location("validate_phase_dynamic", script_path)
        if spec is None or spec.loader is None:
            return None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        validator_cls = getattr(module, "PhaseValidator", None)
        if validator_cls is None:
            return None
        return validator_cls(workflow_config_path)
    except Exception:
        return None


def phase_allows_parallel_agents(state: DecisionState, workflow_config_path: str) -> bool:
    """
    Hard gate using existing workflow validation logic.

    Default behavior:
    - Parallel execution is only considered in phase 4.
    - If validator is available, verify phase can write Java source.
    """
    if state.phase != 4:
        return False

    validator = _load_phase_validator(workflow_config_path)
    if validator is None:
        return True

    allowed, _ = validator.validate_write(
        state.phase, "src/main/java/com/example/MetaControllerProbe.java"
    )
    return bool(allowed)


def derive_weights(state: DecisionState) -> Dict[str, float]:
    """Context-sensitive Lagrange multipliers for objective terms."""
    return {
        "lambda_time": 0.08 + (0.28 * state.deadline_pressure),
        "lambda_compute": 0.05 + (0.20 * (1.0 - state.compute_budget)),
        "lambda_risk": 0.10 + (0.34 * state.failure_impact) + (0.22 * state.blast_radius),
        "lambda_merge": 0.07 + (0.18 * state.coupling),
    }


def estimate_execution_metrics(state: DecisionState, agents: int) -> Dict[str, float]:
    """
    Estimate normalized metrics for an execution mode with N agents.

    Amdahl-inspired time estimate with coordination and merge overhead:
      Tn = ((1-p) + p/n) + Tcoord + Tmerge
    """
    agents = max(1, int(agents))
    p = state.parallelizable_fraction
    workload_pressure = clamp((state.task_count - 8) / 32.0)

    coord_overhead = (
        0.14
        * math.log2(agents + 1.0)
        * (1.0 + state.coupling + state.novelty)
        * (1.0 - (0.35 * workload_pressure))
    )
    merge_overhead = (
        0.09
        * (agents - 1)
        * (0.6 + state.coupling)
        * (1.0 - (0.50 * workload_pressure))
    )
    time_norm = ((1.0 - p) + (p / agents)) + coord_overhead + (0.5 * merge_overhead)

    quality = clamp(
        0.62
        + (0.24 * state.confidence)
        - (0.14 * state.novelty)
        - (0.10 * state.deadline_pressure)
        + (0.05 * math.log2(agents))
        + (0.03 * workload_pressure * math.log2(agents))
        - (0.10 * state.coupling * (agents - 1) / 5.0)
        - (0.06 * state.novelty * (agents - 1) / 5.0)
    )

    compute_norm = (
        (agents / 6.0)
        * (0.75 + (0.35 * state.novelty))
        * (1.0 - (0.30 * workload_pressure))
    )
    risk_prob = clamp(
        0.05
        + (0.30 * state.novelty)
        + (0.22 * state.coupling)
        + (0.03 * (agents - 1) * (1.0 - (0.55 * workload_pressure)))
    )
    risk_norm = clamp(risk_prob * (0.5 + (0.5 * state.failure_impact) + (0.4 * state.blast_radius)), 0.0, 1.5)
    rework_risk = clamp(
        0.08
        + (0.55 * state.coupling)
        + (0.18 * state.novelty)
        + (0.02 * (agents - 1) * (1.0 - (0.55 * workload_pressure)))
    )

    return {
        "quality": quality,
        "time_norm": time_norm,
        "compute_norm": compute_norm,
        "risk_norm": risk_norm,
        "merge_norm": merge_overhead,
        "rework_risk": rework_risk,
        "workload_pressure": workload_pressure,
    }


def compute_base_reward(metrics: Dict[str, float], weights: Dict[str, float]) -> float:
    return (
        metrics["quality"]
        - (weights["lambda_time"] * metrics["time_norm"])
        - (weights["lambda_compute"] * metrics["compute_norm"])
        - (weights["lambda_risk"] * metrics["risk_norm"])
    )


def compute_objective_score(metrics: Dict[str, float], weights: Dict[str, float]) -> float:
    return compute_base_reward(metrics, weights) - (weights["lambda_merge"] * metrics["merge_norm"])


def evaluate_execution_modes(
    state: DecisionState,
    weights: Dict[str, float],
    tuning: ControllerTuning,
    parallel_allowed: bool,
) -> Dict[str, Any]:
    max_agents = min(tuning.max_parallel_agents, max(1, state.task_count))
    high_risk_entanglement = (
        state.coupling >= 0.85
        and state.novelty >= 0.75
        and state.blast_radius >= 0.75
        and state.failure_impact >= 0.80
    )

    if not parallel_allowed or state.task_count <= 3 or high_risk_entanglement:
        candidate_agents = [1]
    else:
        candidate_agents = list(range(1, max_agents + 1))

    candidates: List[Dict[str, Any]] = []
    for agents in candidate_agents:
        metrics = estimate_execution_metrics(state, agents)
        base_reward = compute_base_reward(metrics, weights)
        throughput_bonus = (
            0.055
            * metrics["workload_pressure"]
            * state.parallelizable_fraction
            * math.log2(agents)
        )
        score = compute_objective_score(metrics, weights) + throughput_bonus
        candidates.append(
            {
                "agents": agents,
                "mode": "single_orchestrator" if agents == 1 else "orchestrator_plus_parallel_agents",
                "metrics": {k: round_float(v) for k, v in metrics.items()},
                "base_reward": round_float(base_reward),
                "parallel_throughput_bonus": round_float(throughput_bonus),
                "score": round_float(score),
            }
        )

    candidates.sort(key=lambda item: item["agents"])
    by_agents = {item["agents"]: item for item in candidates}
    single = by_agents[1]

    best = max(candidates, key=lambda item: item["score"])
    margin_vs_single = round_float(best["score"] - single["score"])

    if best["agents"] > 1 and margin_vs_single < tuning.parallel_margin_min:
        best = single
        margin_vs_single = 0.0

    return {
        "parallel_allowed": parallel_allowed,
        "candidate_count": len(candidates),
        "candidates": candidates,
        "recommended_mode": best["mode"],
        "recommended_agents": best["agents"],
        "score_margin_vs_single": margin_vs_single,
    }


def evaluate_planning_depth(
    state: DecisionState,
    weights: Dict[str, float],
) -> Dict[str, Any]:
    """
    Value-of-computation style planning depth search.

    Utility(k) = P(success|k) * V(success) - lambda_time * PlanningOverhead(k)
    VOC(k) = Utility(k) - Utility(k-1)
    """
    max_depth = min(6, max(2, math.ceil(state.task_count / 2)))
    value_success = 1.0 + (1.30 * state.failure_impact) + (0.60 * state.blast_radius)

    base_success = clamp(
        0.45
        + (0.32 * state.confidence)
        - (0.24 * state.novelty)
        - (0.18 * state.coupling)
    )

    utilities: List[Dict[str, float]] = []
    previous_utility: Optional[float] = None

    for depth in range(0, max_depth + 1):
        success_prob = clamp(
            base_success
            + (0.25 * (1.0 - math.exp(-0.85 * depth)))
            - (0.03 * max(0, depth - 4))
        )
        planning_overhead = 0.09 * depth * (1.0 + (0.7 * state.novelty))
        utility = (success_prob * value_success) - (weights["lambda_time"] * planning_overhead)
        voc = 0.0 if previous_utility is None else (utility - previous_utility)
        utilities.append(
            {
                "depth": float(depth),
                "success_prob": round_float(success_prob),
                "planning_overhead": round_float(planning_overhead),
                "utility": round_float(utility),
                "voc": round_float(voc),
            }
        )
        previous_utility = utility

    best_row = max(utilities, key=lambda row: row["utility"])
    recommended_depth = int(best_row["depth"])

    return {
        "recommended_mode": "fast_execute" if recommended_depth <= 1 else "decompose_then_execute",
        "recommended_depth": recommended_depth,
        "value_success": round_float(value_success),
        "utility_curve": utilities,
    }


def evaluate_cognition_mode(state: DecisionState, tuning: ControllerTuning) -> Dict[str, Any]:
    """
    Instinctual (System-1) vs deliberative (System-2) control gate.

    The threshold gate prevents instinct use in high novelty/blast scenarios.
    """
    instinct_score = (
        (1.15 * state.confidence)
        - (0.95 * state.novelty)
        - (0.85 * state.blast_radius)
        - (0.55 * state.failure_impact)
    )

    threshold_pass = (
        state.confidence >= tuning.instinct_confidence_min
        and state.novelty <= tuning.instinct_novelty_max
        and state.blast_radius <= tuning.instinct_blast_radius_max
    )

    recommended_mode = "instinctual" if threshold_pass and instinct_score > 0.0 else "deliberative"
    return {
        "recommended_mode": recommended_mode,
        "instinct_score": round_float(instinct_score),
        "threshold_pass": threshold_pass,
        "thresholds": {
            "confidence_min": tuning.instinct_confidence_min,
            "novelty_max": tuning.instinct_novelty_max,
            "blast_radius_max": tuning.instinct_blast_radius_max,
        },
    }


def evaluate_chunking(
    state: DecisionState,
    recommended_agents: int,
) -> Dict[str, Any]:
    """
    Minimize batching objective:
      J(b) = alpha*(N/b) + beta*(b^gamma / N) + delta*DependencyCut(b)
    """
    n_tasks = max(1, state.task_count)
    max_chunk = min(12, n_tasks)

    alpha = 0.65 + (1.35 * state.deadline_pressure)
    beta = 0.55 + (1.10 * state.coupling) + (0.75 * state.novelty)
    gamma = 1.22
    delta = 0.40 + (1.05 * state.coupling)

    candidates: List[Dict[str, float]] = []
    for chunk_size in range(1, max_chunk + 1):
        dependency_cut = (max(0, chunk_size - 2) ** 1.15) / max(1, n_tasks)
        objective = (
            (alpha * (n_tasks / chunk_size))
            + (beta * ((chunk_size ** gamma) / n_tasks))
            + (delta * dependency_cut)
        )
        chunk_count = int(math.ceil(n_tasks / chunk_size))
        candidates.append(
            {
                "chunk_size": float(chunk_size),
                "chunk_count": float(chunk_count),
                "objective": round_float(objective),
            }
        )

    best = min(candidates, key=lambda row: row["objective"])
    chunk_size = int(best["chunk_size"])
    chunk_count = int(best["chunk_count"])

    if recommended_agents > 1 and chunk_count < recommended_agents:
        chunk_count = recommended_agents
        chunk_size = int(math.ceil(n_tasks / chunk_count))

    return {
        "recommended_chunk_size": chunk_size,
        "recommended_chunk_count": chunk_count,
        "objective_curve": candidates,
    }


def evaluate_reward_shaping(
    execution: Dict[str, Any],
    tuning: ControllerTuning,
) -> Dict[str, Any]:
    """
    Reward shaping for parallel preference with quality/rework guardrails.
    """
    candidates = execution["candidates"]
    single = next(item for item in candidates if item["agents"] == 1)
    selected = next(item for item in candidates if item["agents"] == execution["recommended_agents"])

    best_parallel = None
    for candidate in candidates:
        if candidate["agents"] > 1:
            if best_parallel is None or candidate["base_reward"] > best_parallel["base_reward"]:
                best_parallel = candidate

    def shaped_reward(candidate: Dict[str, Any]) -> Dict[str, float]:
        base_reward = float(candidate["base_reward"])
        quality = float(candidate["metrics"]["quality"])
        rework_risk = float(candidate["metrics"]["rework_risk"])
        bonus_applied = (
            candidate["agents"] > 1
            and quality >= tuning.quality_floor_for_parallel_bonus
            and rework_risk <= tuning.rework_ceiling_for_parallel_bonus
        )
        bonus = tuning.parallel_reward_beta if bonus_applied else 0.0
        return {
            "base": base_reward,
            "shaped": base_reward + bonus,
            "bonus_applied": bonus_applied,
            "bonus": bonus,
        }

    single_reward = shaped_reward(single)
    selected_reward = shaped_reward(selected)
    parallel_pref_enabled = False
    best_parallel_reward = None

    if best_parallel is not None:
        best_parallel_reward = shaped_reward(best_parallel)
        parallel_pref_enabled = (
            best_parallel_reward["shaped"] > single_reward["shaped"] + tuning.parallel_margin_min
        )

    return {
        "parallel_reward_beta": tuning.parallel_reward_beta,
        "quality_floor": tuning.quality_floor_for_parallel_bonus,
        "rework_ceiling": tuning.rework_ceiling_for_parallel_bonus,
        "parallel_bonus_applied": selected_reward["bonus_applied"],
        "base_reward_selected": round_float(selected_reward["base"]),
        "shaped_reward_selected": round_float(selected_reward["shaped"]),
        "base_reward_single": round_float(single_reward["base"]),
        "shaped_reward_single": round_float(single_reward["shaped"]),
        "parallel_preference_enabled": parallel_pref_enabled,
        "best_parallel_agents": None if best_parallel is None else best_parallel["agents"],
        "best_parallel_shaped_reward": None
        if best_parallel_reward is None
        else round_float(best_parallel_reward["shaped"]),
    }


def generate_recommendations(
    state: DecisionState,
    execution: Dict[str, Any],
    planning: Dict[str, Any],
    cognition: Dict[str, Any],
    chunking: Dict[str, Any],
    reward: Dict[str, Any],
) -> List[str]:
    notes: List[str] = []

    if not execution["parallel_allowed"]:
        notes.append(
            "Phase gate active: keep a single orchestrator and avoid parallel worker spin-up."
        )
    elif execution["recommended_agents"] > 1:
        notes.append(
            f"Use orchestrator + {execution['recommended_agents'] - 1} parallel agents for execution."
        )
    else:
        notes.append("Use a single orchestrator; coordination overhead outweighs parallel gain.")

    if planning["recommended_mode"] == "decompose_then_execute":
        notes.append(
            f"Decompose first (depth={planning['recommended_depth']}) before execution."
        )
    else:
        notes.append("Execute quickly with minimal decomposition overhead.")

    if cognition["recommended_mode"] == "instinctual":
        notes.append("Instinctual mode is safe here: confidence is high and novelty/blast are low.")
    else:
        notes.append("Use deliberative mode: uncertainty or blast radius is high.")

    notes.append(
        f"Batch into chunks of {chunking['recommended_chunk_size']} task(s) "
        f"across {chunking['recommended_chunk_count']} chunk(s)."
    )

    if execution["recommended_agents"] > 1 and reward["parallel_bonus_applied"]:
        notes.append("Parallel reward bonus can be applied without violating quality/rework guardrails.")
    elif execution["recommended_agents"] > 1:
        notes.append("Do not apply parallel bonus yet; quality or rework guardrails are not met.")

    if state.phase == 4 and not reward["parallel_preference_enabled"]:
        notes.append("Keep neutral incentive: parallel is not consistently better than single-agent mode.")

    return notes


def decide(
    state: DecisionState,
    workflow_config_path: str = ".claude/skills/feature-request/references/feature-workflow.yaml",
    tuning: Optional[ControllerTuning] = None,
) -> Dict[str, Any]:
    tuning = tuning or ControllerTuning()
    weights = derive_weights(state)
    parallel_allowed = phase_allows_parallel_agents(state, workflow_config_path)
    execution = evaluate_execution_modes(state, weights, tuning, parallel_allowed)
    planning = evaluate_planning_depth(state, weights)
    cognition = evaluate_cognition_mode(state, tuning)
    chunking = evaluate_chunking(state, execution["recommended_agents"])
    reward = evaluate_reward_shaping(execution, tuning)
    recommendations = generate_recommendations(
        state, execution, planning, cognition, chunking, reward
    )

    return {
        "state": asdict(state),
        "weights": {k: round_float(v) for k, v in weights.items()},
        "execution": execution,
        "planning": planning,
        "cognition": cognition,
        "chunking": chunking,
        "reward_shaping": reward,
        "recommendations": recommendations,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Brain-inspired strategy selector for feature workflow agents."
    )
    parser.add_argument(
        "--state-file",
        type=str,
        help="Path to JSON file with DecisionState fields.",
    )
    parser.add_argument(
        "--implementation-plan",
        type=str,
        help="Optional path to implementation-plan.md used to auto-infer DecisionState fields.",
    )
    parser.add_argument(
        "--state-json",
        type=str,
        help="Inline JSON object with DecisionState fields.",
    )
    parser.add_argument(
        "--phase",
        type=int,
        choices=[1, 2, 3, 4],
        help="Override phase in input state.",
    )
    parser.add_argument(
        "--tuning-file",
        type=str,
        help="Path to JSON file with ControllerTuning fields.",
    )
    parser.add_argument(
        "--workflow-config",
        type=str,
        default=".claude/skills/feature-request/references/feature-workflow.yaml",
        help="Path to workflow YAML config used by validate-phase gating.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output machine-readable JSON.",
    )
    parser.add_argument(
        "--out",
        type=str,
        help="Optional output file path to persist the decision JSON.",
    )
    return parser.parse_args()


def load_json_file(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def extract_task_breakdown_section(text: str) -> str:
    marker = re.search(r"(?im)^\s*task breakdown\s*$", text)
    if marker is None:
        return text

    tail = text[marker.start():]
    end_marker = re.search(r"(?im)^\s*---\s*$", tail)
    if end_marker is None:
        return tail
    return tail[:end_marker.start()]


def infer_state_from_implementation_plan(plan_path: Path) -> Dict[str, Any]:
    text = plan_path.read_text(encoding="utf-8")
    task_section = extract_task_breakdown_section(text)

    checkbox_tasks = len(re.findall(r"(?im)^\s*-\s*\[(?: |x|X)\]\s+", task_section))
    bullet_tasks = len(re.findall(r"(?im)^\s*-\s+", task_section))
    file_markers = len(re.findall(r"(?im)^\s*file:\s+", text))
    phase_markers = len(re.findall(r"(?im)^\s*phase\s+\d+\s*:", task_section))

    inferred_task_count = checkbox_tasks or bullet_tasks or max(1, file_markers)
    layer_tokens = set(re.findall(r"\b(handler|domain|proxy|security|config|common)\b", text, re.IGNORECASE))
    layer_coverage = len(layer_tokens) / 6.0

    create_count = len(re.findall(r"\b(create|add|introduce)\b", text, re.IGNORECASE))
    modify_count = len(re.findall(r"\b(update|modify|migrate|refactor|replace)\b", text, re.IGNORECASE))
    independence_ratio = create_count / max(1, create_count + modify_count)

    parallelizable_fraction = clamp(
        0.48 + (0.30 * layer_coverage) + (0.22 * independence_ratio),
        high=0.95,
    )
    confidence = clamp(
        0.52
        + (0.16 * min(1.0, file_markers / 16.0))
        + (0.10 * min(1.0, phase_markers / 6.0))
    )
    coupling = clamp(0.20 + (0.32 * (1.0 - independence_ratio)) + (0.08 * layer_coverage))
    deadline_pressure = clamp(0.46 + (0.02 * min(10, phase_markers)))

    return {
        "task_count": inferred_task_count,
        "parallelizable_fraction": round_float(parallelizable_fraction),
        "confidence": round_float(confidence),
        "coupling": round_float(coupling),
        "deadline_pressure": round_float(deadline_pressure),
    }


def resolve_implementation_plan_path(
    explicit_path: Optional[str],
    out_path: Optional[str],
) -> Optional[Path]:
    if explicit_path:
        candidate = Path(explicit_path).expanduser()
        if candidate.exists():
            return candidate
        return None

    if out_path:
        out = Path(out_path).expanduser()
        # Expected shape: <feature-dir>/decision-support/preflight-meta-controller.json
        if out.parent.name == "decision-support":
            candidate = out.parent.parent / "implementation-plan.md"
            if candidate.exists():
                return candidate
    return None


def main() -> None:
    args = parse_args()

    state_payload: Dict[str, Any] = {}
    state_inference: Optional[Dict[str, Any]] = None

    inferred_plan_path = resolve_implementation_plan_path(args.implementation_plan, args.out)
    if inferred_plan_path is not None:
        inferred = infer_state_from_implementation_plan(inferred_plan_path)
        state_payload.update(inferred)
        state_inference = {
            "source": str(inferred_plan_path),
            "inferred_fields": inferred,
        }

    if args.state_file:
        state_payload.update(load_json_file(args.state_file))
    if args.state_json:
        state_payload.update(json.loads(args.state_json))
    if args.phase is not None:
        state_payload["phase"] = args.phase

    tuning_payload: Optional[Dict[str, Any]] = None
    if args.tuning_file:
        tuning_payload = load_json_file(args.tuning_file)

    state = DecisionState.from_dict(state_payload)
    tuning = ControllerTuning.from_dict(tuning_payload)
    decision = decide(state, workflow_config_path=args.workflow_config, tuning=tuning)
    if state_inference is not None:
        decision["state_inference"] = state_inference

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(decision, handle, indent=2)

    if args.json:
        print(json.dumps(decision, indent=2))
        return

    print("Meta-Controller Recommendation")
    print("=" * 32)
    print(f"Phase: {decision['state']['phase']}")
    print(f"Execution: {decision['execution']['recommended_mode']} "
          f"(agents={decision['execution']['recommended_agents']})")
    print(f"Planning: {decision['planning']['recommended_mode']} "
          f"(depth={decision['planning']['recommended_depth']})")
    print(f"Cognition: {decision['cognition']['recommended_mode']}")
    print(
        f"Chunking: size={decision['chunking']['recommended_chunk_size']}, "
        f"count={decision['chunking']['recommended_chunk_count']}"
    )
    print(f"Parallel Preference Enabled: {decision['reward_shaping']['parallel_preference_enabled']}")
    print("\nActionable Notes:")
    for note in decision["recommendations"]:
        print(f"- {note}")


if __name__ == "__main__":
    main()
