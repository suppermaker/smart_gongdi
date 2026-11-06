import pytest

from sitepilot_agent_runtime.platform_agents import (
    PlatformAgentRole,
    PlatformDagCompiler,
    PlatformIntent,
    PlatformPlanValidator,
    PlatformRequest,
    RuleBasedPlatformRouter,
)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("text", "output", "expected"),
    [
        ("塔吊月检有哪些规范要求", "answer", PlatformIntent.KNOWLEDGE_QA),
        ("A项目本月高风险隐患有多少", "answer", PlatformIntent.DATABASE_QA),
        ("根据规范分析本月高风险隐患", "answer", PlatformIntent.HYBRID_QA),
        ("审查这份施工方案并给出修改建议", "review", PlatformIntent.DOCUMENT_REVIEW),
        ("生成项目风险月报", "report", PlatformIntent.REPORT_GENERATION),
        ("识别合同甲乙方和金额", "ocr", PlatformIntent.OCR_EXTRACTION),
        ("调查塔吊离线根因", "answer", PlatformIntent.RISK_INVESTIGATION),
        ("帮我处理一下", "answer", PlatformIntent.HUMAN_CLARIFICATION),
    ],
)
async def test_platform_router_covers_contract_modules(text, output, expected):
    result = await RuleBasedPlatformRouter().classify(
        PlatformRequest("task-1", text, requested_output=output)
    )
    assert result == expected


def test_hybrid_and_report_plans_are_dynamic_parallel_dags():
    compiler = PlatformDagCompiler()
    hybrid = compiler.compile(PlatformIntent.HYBRID_QA, "hybrid")
    levels = PlatformPlanValidator().validate(hybrid)
    assert {task.role for task in levels[0]} == {
        PlatformAgentRole.KNOWLEDGE,
        PlatformAgentRole.DATABASE,
    }
    assert levels[-1][0].role == PlatformAgentRole.VERIFIER

    report = compiler.compile(PlatformIntent.REPORT_GENERATION, "report")
    report_levels = PlatformPlanValidator().validate(report)
    assert {task.role for task in report_levels[0]} == {
        PlatformAgentRole.KNOWLEDGE,
        PlatformAgentRole.DATABASE,
    }
    assert any(task.role == PlatformAgentRole.REPORT for task in report.tasks)
    assert report_levels[-1][0].role == PlatformAgentRole.VERIFIER


def test_all_specialists_receive_only_role_allowlisted_tools():
    compiler = PlatformDagCompiler()
    for intent in PlatformIntent:
        plan = compiler.compile(intent, intent.value)
        PlatformPlanValidator().validate(plan)
        for task in plan.tasks:
            assert "tenant_id" not in task.allowed_tools
            assert "database_url" not in task.allowed_tools
            assert "credential" not in task.allowed_tools
