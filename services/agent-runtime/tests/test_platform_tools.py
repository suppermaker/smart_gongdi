import pytest

from sitepilot_agent_runtime.database_runtime import ReadOnlySqlGuard, SqlPolicy, SqlPolicyError
from sitepilot_agent_runtime.knowledge_runtime import KnowledgeChunk, ProjectKnowledgeIndex


def test_project_knowledge_search_is_isolated_and_returns_citations():
    index = ProjectKnowledgeIndex()
    index.add(
        KnowledgeChunk(
            "tenant",
            "project-a",
            "doc-a",
            "chunk-a",
            "塔吊月检必须检查限位器和制动器",
            "page:12",
            "v1",
        )
    )
    index.add(
        KnowledgeChunk(
            "tenant", "project-b", "doc-b", "chunk-b", "塔吊月检可以跳过制动器", "page:3", "v1"
        )
    )
    hits = index.search("tenant", "project-a", "塔吊月检制动器")
    assert [hit.chunk_id for hit in hits] == ["chunk-a"]
    assert hits[0].document_id == "doc-a"
    assert hits[0].source_location == "page:12"


def test_sql_guard_injects_project_scope_and_limit():
    guard = ReadOnlySqlGuard()
    policy = SqlPolicy(frozenset({"safety_inspection", "project_info"}), max_rows=100)
    sql = guard.validate_and_scope(
        "SELECT risk_level, count(*) FROM safety_inspection GROUP BY risk_level",
        "project-a",
        policy,
    )
    assert "WHERE project_id = 'project-a'" in sql
    assert sql.endswith("LIMIT 100")


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM safety_inspection",
        "SELECT * FROM secret_table",
        "SELECT * FROM safety_inspection; DROP TABLE safety_inspection",
        "UPDATE safety_inspection SET risk_level='LOW'",
    ],
)
def test_sql_guard_fails_closed(sql):
    with pytest.raises(SqlPolicyError):
        ReadOnlySqlGuard().validate_and_scope(
            sql, "project-a", SqlPolicy(frozenset({"safety_inspection"}))
        )
