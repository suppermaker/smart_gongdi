"""Platform-level multi-agent orchestration for the contract application modules."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Protocol


class PlatformIntent(StrEnum):
    KNOWLEDGE_QA = "knowledge_qa"
    DATABASE_QA = "database_qa"
    HYBRID_QA = "hybrid_qa"
    DOCUMENT_REVIEW = "document_review"
    REPORT_GENERATION = "report_generation"
    OCR_EXTRACTION = "ocr_extraction"
    RISK_INVESTIGATION = "risk_investigation"
    HUMAN_CLARIFICATION = "human_clarification"


class PlatformAgentRole(StrEnum):
    SUPERVISOR = "query_supervisor"
    KNOWLEDGE = "knowledge_agent"
    DATABASE = "database_agent"
    REVIEW = "review_agent"
    REPORT = "report_agent"
    OCR = "ocr_agent"
    RISK = "risk_investigation_agent"
    SYNTHESIS = "synthesis_agent"
    VERIFIER = "answer_verifier"


ROLE_TOOLS = {
    PlatformAgentRole.SUPERVISOR: {"read_conversation", "request_clarification"},
    PlatformAgentRole.KNOWLEDGE: {"search_knowledge", "get_document_chunk"},
    PlatformAgentRole.DATABASE: {"get_schema_snapshot", "execute_readonly_query"},
    PlatformAgentRole.REVIEW: {"get_review_template", "search_knowledge", "read_document"},
    PlatformAgentRole.REPORT: {"get_report_template", "bind_report_data", "render_report"},
    PlatformAgentRole.OCR: {"read_source_object", "extract_document_fields"},
    PlatformAgentRole.RISK: {"start_risk_investigation", "read_risk_investigation"},
    PlatformAgentRole.SYNTHESIS: {"read_agent_outputs"},
    PlatformAgentRole.VERIFIER: {"read_agent_outputs", "verify_citations"},
}


@dataclass(frozen=True)
class PlatformRequest:
    task_id: str
    user_input: str
    attachment_refs: tuple[str, ...] = ()
    requested_output: str = "answer"
    context: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class PlatformAgentTask:
    task_id: str
    role: PlatformAgentRole
    objective: str
    dependencies: tuple[str, ...] = ()
    allowed_tools: tuple[str, ...] = ()


@dataclass(frozen=True)
class PlatformPlan:
    intent: PlatformIntent
    tasks: tuple[PlatformAgentTask, ...]
    needs_clarification: bool = False
    reason_codes: tuple[str, ...] = ()


class IntentProvider(Protocol):
    async def classify(self, request: PlatformRequest) -> PlatformIntent: ...


class RuleBasedPlatformRouter:
    async def classify(self, request: PlatformRequest) -> PlatformIntent:
        text = request.user_input.lower()
        output = request.requested_output.lower()
        if output in {"report", "docx", "pdf"} or any(
            word in text for word in ("报告", "月报", "周报")
        ):
            return PlatformIntent.REPORT_GENERATION
        if output == "review" or any(word in text for word in ("审查", "合规", "修改建议")):
            return PlatformIntent.DOCUMENT_REVIEW
        if output == "ocr" or any(
            word in text for word in ("识别", "提取合同", "身份证", "车牌", "发票")
        ):
            return PlatformIntent.OCR_EXTRACTION
        if any(word in text for word in ("调查", "根因", "整改是否完成")):
            return PlatformIntent.RISK_INVESTIGATION
        database = any(
            word in text for word in ("库存", "考勤", "本月", "数量", "进度", "成本", "数据库")
        )
        knowledge = any(word in text for word in ("规范", "制度", "条款", "文档", "要求"))
        if database and knowledge:
            return PlatformIntent.HYBRID_QA
        if database:
            return PlatformIntent.DATABASE_QA
        if knowledge:
            return PlatformIntent.KNOWLEDGE_QA
        return PlatformIntent.HUMAN_CLARIFICATION


class PlatformDagCompiler:
    def compile(self, intent: PlatformIntent, task_id: str) -> PlatformPlan:
        prefix = f"platform-{task_id}"
        tasks: list[PlatformAgentTask] = []

        def add(role: PlatformAgentRole, objective: str, dependencies: tuple[str, ...] = ()) -> str:
            child_id = f"{prefix}-{role.value}"
            tasks.append(
                PlatformAgentTask(
                    child_id,
                    role,
                    objective,
                    dependencies,
                    tuple(sorted(ROLE_TOOLS[role])),
                )
            )
            return child_id

        if intent == PlatformIntent.HUMAN_CLARIFICATION:
            add(
                PlatformAgentRole.SUPERVISOR,
                "Ask which project data source and output type the user needs",
            )
            return PlatformPlan(intent, tuple(tasks), True, ("ROUTE_AMBIGUOUS",))

        sources: list[str] = []
        if intent in {PlatformIntent.KNOWLEDGE_QA, PlatformIntent.HYBRID_QA}:
            sources.append(
                add(PlatformAgentRole.KNOWLEDGE, "Retrieve project-scoped knowledge with citations")
            )
        if intent in {PlatformIntent.DATABASE_QA, PlatformIntent.HYBRID_QA}:
            sources.append(
                add(
                    PlatformAgentRole.DATABASE,
                    "Build and execute a permission-scoped read-only query",
                )
            )
        if intent == PlatformIntent.DOCUMENT_REVIEW:
            sources.append(
                add(
                    PlatformAgentRole.REVIEW,
                    "Review the document against versioned standards and templates",
                )
            )
        if intent == PlatformIntent.OCR_EXTRACTION:
            sources.append(
                add(
                    PlatformAgentRole.OCR,
                    "Extract requested fields with confidence and source positions",
                )
            )
        if intent == PlatformIntent.RISK_INVESTIGATION:
            sources.append(
                add(
                    PlatformAgentRole.RISK,
                    "Bridge to the existing evidence-driven risk investigation system",
                )
            )
        if intent == PlatformIntent.REPORT_GENERATION:
            knowledge = add(
                PlatformAgentRole.KNOWLEDGE, "Retrieve report evidence and source citations"
            )
            database = add(PlatformAgentRole.DATABASE, "Retrieve authoritative report metrics")
            sources.extend((knowledge, database))
            sources = [
                add(PlatformAgentRole.REPORT, "Render the selected report template", tuple(sources))
            ]

        synthesis = add(
            PlatformAgentRole.SYNTHESIS,
            "Synthesize specialist outputs without inventing facts",
            tuple(sources),
        )
        add(
            PlatformAgentRole.VERIFIER,
            "Verify citations, permissions and output completeness",
            (synthesis,),
        )
        plan = PlatformPlan(intent, tuple(tasks))
        PlatformPlanValidator().validate(plan)
        return plan


class PlatformPlanValidator:
    def validate(self, plan: PlatformPlan) -> list[list[PlatformAgentTask]]:
        by_id: dict[str, PlatformAgentTask] = {}
        for task in plan.tasks:
            if task.task_id in by_id:
                raise PlatformProtocolError("PLATFORM_TASK_DUPLICATE", task.task_id)
            by_id[task.task_id] = task
            forbidden = set(task.allowed_tools) - ROLE_TOOLS[task.role]
            if forbidden:
                raise PlatformProtocolError("PLATFORM_TOOL_DENIED", ",".join(sorted(forbidden)))
        for task in plan.tasks:
            missing = set(task.dependencies) - by_id.keys()
            if missing:
                raise PlatformProtocolError(
                    "PLATFORM_DEPENDENCY_MISSING", ",".join(sorted(missing))
                )
        levels: list[list[PlatformAgentTask]] = []
        completed: set[str] = set()
        while len(completed) < len(plan.tasks):
            ready = [
                task
                for task in plan.tasks
                if task.task_id not in completed and set(task.dependencies) <= completed
            ]
            if not ready:
                raise PlatformProtocolError("PLATFORM_DAG_CYCLE", plan.intent)
            levels.append(ready)
            completed.update(task.task_id for task in ready)
        return levels


class PlatformProtocolError(RuntimeError):
    def __init__(self, code: str, detail: object) -> None:
        self.code = code
        super().__init__(f"{code}: {detail}")
