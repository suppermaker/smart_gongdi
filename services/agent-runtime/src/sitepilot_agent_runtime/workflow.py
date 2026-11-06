"""Deterministic durable investigation workflow with optional M9 routing."""

from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError

with workflow.unsafe.imports_passed_through():
    from sitepilot_agent_runtime.activities import InvestigationActivities
    from sitepilot_agent_runtime.contracts import (
        CONTRACT_VERSION,
        ExecuteRoutedDagInput,
        FinalizeInvestigationInput,
        HumanAnswer,
        HumanQuestion,
        InvestigationInput,
        InvestigationResult,
        InvestigationState,
        LoadRoutingContextInput,
        LoadTaskContextInput,
        PersistRoutingDecisionInput,
        PersistStatusInput,
        RecordHumanAnswerInput,
        ResultKind,
        RouteInvestigationInput,
        RoutingContext,
        WorkflowPhase,
    )

_ACTIVITY_START_TO_CLOSE = timedelta(seconds=10)
_ACTIVITY_SCHEDULE_TO_CLOSE = timedelta(seconds=30)
_RETRY_POLICY = RetryPolicy(
    initial_interval=timedelta(milliseconds=100),
    backoff_coefficient=2.0,
    maximum_interval=timedelta(seconds=2),
    maximum_attempts=4,
    non_retryable_error_types=[
        "InvalidTask",
        "AuthorizationDenied",
        "ContractViolation",
        "ROUTING_DECISION_INVALID",
        "JEV_RESPONSE_INVALID",
        "JEV_HTTP_ERROR_4XX",
    ],
)


@workflow.defn
class InvestigationWorkflow:
    def __init__(self) -> None:
        self._state = InvestigationState(CONTRACT_VERSION, "uninitialized", WorkflowPhase.CREATED)
        self._input: InvestigationInput | None = None
        self._deadline = None

    @workflow.run
    async def run(self, value: InvestigationInput) -> InvestigationResult:
        self._input = value
        self._state = InvestigationState(CONTRACT_VERSION, value.task_id, WorkflowPhase.CREATED)
        self._deadline = workflow.now() + timedelta(seconds=value.budget.workflow_deadline_seconds)
        if value.contract_version != CONTRACT_VERSION:
            return self._failed("UNSUPPORTED_CONTRACT_VERSION")
        if value.budget.max_activity_calls < 0 or value.budget.workflow_deadline_seconds <= 0:
            return self._failed("INVALID_BUDGET")
        if value.routing_enabled and value.max_routing_revisions < 1:
            return self._failed("INVALID_ROUTING_BUDGET")

        try:
            self._state.phase = WorkflowPhase.LOADING_CONTEXT
            context = await self._activity(
                InvestigationActivities.load_task_context,
                LoadTaskContextInput(
                    CONTRACT_VERSION,
                    value.task_id,
                    value.control_plane_base_url,
                    value.correlation_id,
                    value.traceparent,
                ),
            )
            if value.routing_enabled and workflow.patched("jev-routing-v1"):
                routed = await self._run_routing(value)
                if routed is not None:
                    return routed

            self._state.phase = WorkflowPhase.PERSISTING_STARTED
            await self._activity(
                InvestigationActivities.persist_status,
                PersistStatusInput(
                    CONTRACT_VERSION,
                    value.task_id,
                    "INVESTIGATION_STARTED",
                    f"{value.idempotency_key}:started",
                    value.correlation_id,
                    value.traceparent,
                ),
            )
            if value.human_question is not None:
                answer_error = await self._wait_for_answer(value, value.human_question)
                if answer_error is not None:
                    return answer_error
            self._state.phase = WorkflowPhase.FINALIZING
            finalized = await self._activity(
                InvestigationActivities.finalize_investigation,
                FinalizeInvestigationInput(
                    CONTRACT_VERSION,
                    value.task_id,
                    context,
                    self._state.answer,
                    f"{value.idempotency_key}:finalize",
                    value.correlation_id,
                    value.traceparent,
                ),
            )
        except BudgetExceeded as exc:
            return self._escalated(exc.code)
        except TimeoutError:
            return self._escalated("WORKFLOW_DEADLINE_EXCEEDED")
        except ApplicationError as exc:
            self._state.phase = WorkflowPhase.FAILED
            self._state.detail = exc.type
            return self._failed("ACTIVITY_FAILED")

        self._state.phase = WorkflowPhase.COMPLETED
        return InvestigationResult(
            CONTRACT_VERSION,
            value.task_id,
            ResultKind.COMPLETED,
            finalized.final_status,
            self._state.activity_calls,
        )

    async def _run_routing(self, value: InvestigationInput) -> InvestigationResult | None:
        self._state.phase = WorkflowPhase.ROUTING
        routing_context = await self._activity(
            InvestigationActivities.load_routing_context,
            LoadRoutingContextInput(
                CONTRACT_VERSION,
                value.task_id,
                value.correlation_id,
                value.traceparent,
            ),
        )
        route = await self._route_once(value, routing_context, 0)
        if route.route_disposition in {"HUMAN_CLARIFICATION", "HUMAN_ESCALATION"}:
            question = HumanQuestion(
                "routing-clarification",
                "Provide the missing fact required to safely route this investigation.",
            )
            self._state.phase = WorkflowPhase.WAITING_FOR_CLARIFICATION
            answer_error = await self._wait_for_answer(value, question)
            if answer_error is not None:
                return answer_error
            revised_state = dict(routing_context.state)
            revised_state["human_answer"] = self._state.answer.answer if self._state.answer else ""
            routing_context = RoutingContext(
                routing_context.contract_version,
                routing_context.task_id,
                routing_context.task_version,
                routing_context.graph_revision,
                revised_state,
                routing_context.high_risk,
                routing_context.closure_requested,
                True,
            )
            self._state.phase = WorkflowPhase.REROUTING
            route = await self._route_once(value, routing_context, 1)
            if route.route_disposition in {"HUMAN_CLARIFICATION", "HUMAN_ESCALATION"}:
                return self._escalated("ROUTING_HUMAN_INPUT_INSUFFICIENT")

        self._state.phase = WorkflowPhase.EXECUTING_DAG
        context_assembly_enabled = workflow.patched("context-assembly-v1")
        await self._activity(
            InvestigationActivities.execute_routed_dag,
            ExecuteRoutedDagInput(
                CONTRACT_VERSION,
                route,
                f"{value.idempotency_key}:execute-route:{route.graph_revision}",
                value.correlation_id,
                value.traceparent,
                routing_context.task_version if context_assembly_enabled else 0,
                value.context_policy_version,
            ),
        )
        return None

    async def _route_once(self, value: InvestigationInput, context: RoutingContext, sequence: int):
        if self._state.routing_revisions >= value.max_routing_revisions:
            raise BudgetExceeded("MAX_ROUTING_REVISIONS_EXCEEDED")
        route = await self._activity(
            InvestigationActivities.route_investigation,
            RouteInvestigationInput(
                CONTRACT_VERSION,
                context,
                value.routing_policy_version,
                f"{value.idempotency_key}:route:{context.graph_revision}:{sequence}",
                value.correlation_id,
                value.traceparent,
            ),
        )
        persisted = await self._activity(
            InvestigationActivities.persist_routing_decision,
            PersistRoutingDecisionInput(
                CONTRACT_VERSION,
                route,
                context.task_version,
                f"{value.idempotency_key}:persist-route:{context.graph_revision}:{sequence}",
                value.correlation_id,
                value.traceparent,
            ),
        )
        self._state.routing_decision_id = persisted.decision_id
        self._state.routing_revisions += 1
        return route

    async def _wait_for_answer(
        self, value: InvestigationInput, question: HumanQuestion
    ) -> InvestigationResult | None:
        self._state.phase = (
            WorkflowPhase.WAITING_FOR_CLARIFICATION
            if question.question_id == "routing-clarification"
            else WorkflowPhase.WAITING_FOR_HUMAN
        )
        self._state.pending_question = question
        await workflow.wait_condition(
            lambda: self._state.answer is not None, timeout=self._remaining()
        )
        answer = self._state.answer
        if answer is None or answer.question_id != question.question_id:
            return self._failed("QUESTION_MISMATCH")
        if question.requires_approval and answer.approved is None:
            return self._failed("APPROVAL_REQUIRED")
        self._state.pending_question = None
        self._state.phase = WorkflowPhase.RECORDING_ANSWER
        await self._activity(
            InvestigationActivities.record_human_answer,
            RecordHumanAnswerInput(
                CONTRACT_VERSION,
                value.task_id,
                answer,
                f"{value.idempotency_key}:human:{question.question_id}",
                value.correlation_id,
                value.traceparent,
            ),
        )
        return None

    async def _activity(self, activity_fn, argument):
        if self._input is None or self._deadline is None:
            raise RuntimeError("workflow not initialized")
        if self._state.activity_calls >= self._input.budget.max_activity_calls:
            raise BudgetExceeded("MAX_ACTIVITY_CALLS_EXCEEDED")
        if self._remaining() <= timedelta(0):
            raise BudgetExceeded("WORKFLOW_DEADLINE_EXCEEDED")
        self._state.activity_calls += 1
        return await workflow.execute_activity(
            activity_fn,
            argument,
            start_to_close_timeout=_ACTIVITY_START_TO_CLOSE,
            schedule_to_close_timeout=min(_ACTIVITY_SCHEDULE_TO_CLOSE, self._remaining()),
            retry_policy=_RETRY_POLICY,
        )

    def _remaining(self) -> timedelta:
        if self._deadline is None:
            return timedelta(0)
        return max(self._deadline - workflow.now(), timedelta(0))

    @workflow.signal
    async def submit_human_answer(self, answer: HumanAnswer) -> None:
        self._state.answer = answer

    @workflow.query
    def current_state(self) -> InvestigationState:
        return self._state

    def _escalated(self, code: str) -> InvestigationResult:
        self._state.phase = WorkflowPhase.ESCALATED
        self._state.detail = code
        return InvestigationResult(
            CONTRACT_VERSION,
            self._state.task_id,
            ResultKind.ESCALATED,
            "HUMAN_ESCALATION_REQUIRED",
            self._state.activity_calls,
            code,
        )

    def _failed(self, code: str) -> InvestigationResult:
        self._state.phase = WorkflowPhase.FAILED
        self._state.detail = code
        return InvestigationResult(
            CONTRACT_VERSION,
            self._state.task_id,
            ResultKind.FAILED,
            "FAILED",
            self._state.activity_calls,
            code,
        )


class BudgetExceeded(Exception):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)
