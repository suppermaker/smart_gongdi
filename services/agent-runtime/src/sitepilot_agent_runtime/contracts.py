"""Stable JSON-compatible Temporal payload contracts for investigation workflows."""

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

CONTRACT_VERSION = 1


class WorkflowPhase(StrEnum):
    CREATED = "created"
    LOADING_CONTEXT = "loading_context"
    ROUTING = "routing"
    WAITING_FOR_CLARIFICATION = "waiting_for_clarification"
    EXECUTING_DAG = "executing_dag"
    MERGING_RESULTS = "merging_results"
    REROUTING = "rerouting"
    PERSISTING_STARTED = "persisting_started"
    WAITING_FOR_HUMAN = "waiting_for_human"
    RECORDING_ANSWER = "recording_answer"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    ESCALATED = "escalated"
    FAILED = "failed"


class ResultKind(StrEnum):
    COMPLETED = "completed"
    ESCALATED = "escalated"
    FAILED = "failed"


@dataclass
class BudgetPolicy:
    max_activity_calls: int
    workflow_deadline_seconds: int


@dataclass
class HumanQuestion:
    question_id: str
    prompt: str
    requires_approval: bool = False


@dataclass
class HumanAnswer:
    question_id: str
    answer: str
    approved: bool | None = None
    answered_by: str = "human"


@dataclass
class InvestigationInput:
    contract_version: int
    task_id: str
    control_plane_base_url: str
    idempotency_key: str
    budget: BudgetPolicy
    human_question: HumanQuestion | None = None
    correlation_id: str | None = None
    traceparent: str | None = None
    routing_enabled: bool = False
    routing_policy_version: str = "routing-policy.v1"
    max_routing_revisions: int = 1
    context_policy_version: str = "context-policy.v1"


@dataclass
class InvestigationState:
    contract_version: int
    task_id: str
    phase: WorkflowPhase
    activity_calls: int = 0
    pending_question: HumanQuestion | None = None
    answer: HumanAnswer | None = None
    detail: str | None = None
    routing_decision_id: str | None = None
    routing_revisions: int = 0


@dataclass
class InvestigationResult:
    contract_version: int
    task_id: str
    kind: ResultKind
    final_status: str
    activity_calls: int
    escalation_code: str | None = None
    detail: str | None = None


@dataclass
class LoadTaskContextInput:
    contract_version: int
    task_id: str
    control_plane_base_url: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class TaskContext:
    contract_version: int
    task_id: str
    authoritative_status: str
    risk_reference: str | None = None


@dataclass
class RoutingContext:
    contract_version: int
    task_id: str
    task_version: int
    graph_revision: int
    state: dict[str, Any]
    high_risk: bool = False
    closure_requested: bool = False
    identity_resolved: bool = True


@dataclass
class LoadRoutingContextInput:
    contract_version: int
    task_id: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class RouteInvestigationInput:
    contract_version: int
    context: RoutingContext
    policy_version: str
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class RouteInvestigationOutput:
    contract_version: int
    task_id: str
    graph_revision: int
    decision: dict[str, Any]
    compiled_tasks: list[dict[str, Any]]
    route_disposition: str


@dataclass
class PersistRoutingDecisionInput:
    contract_version: int
    route: RouteInvestigationOutput
    task_version: int
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class PersistRoutingDecisionOutput:
    contract_version: int
    task_id: str
    decision_id: str
    applied: bool


@dataclass
class ExecuteRoutedDagInput:
    contract_version: int
    route: RouteInvestigationOutput
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None
    task_version: int = 0
    context_policy_version: str = "context-policy.v1"


@dataclass
class ExecuteRoutedDagOutput:
    contract_version: int
    task_id: str
    graph_revision: int
    evidence_fingerprint: str
    executed_task_ids: list[str]
    applied: bool
    context_refs: list[dict[str, str]] = field(default_factory=list)


@dataclass
class PersistStatusInput:
    contract_version: int
    task_id: str
    status: str
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class PersistStatusOutput:
    contract_version: int
    task_id: str
    status: str
    applied: bool


@dataclass
class RecordHumanAnswerInput:
    contract_version: int
    task_id: str
    answer: HumanAnswer
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class RecordHumanAnswerOutput:
    contract_version: int
    task_id: str
    applied: bool


@dataclass
class FinalizeInvestigationInput:
    contract_version: int
    task_id: str
    context: TaskContext
    answer: HumanAnswer | None
    idempotency_key: str
    correlation_id: str | None = None
    traceparent: str | None = None


@dataclass
class FinalizeInvestigationOutput:
    contract_version: int
    task_id: str
    final_status: str
    applied: bool
    evidence_references: list[str] = field(default_factory=list)
