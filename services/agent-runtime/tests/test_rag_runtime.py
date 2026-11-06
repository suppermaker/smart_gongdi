import pytest

from sitepilot_agent_runtime.rag_runtime import HybridRagIndex, ParsedSection


class FakeEmbedder:
    async def embed(self, texts):
        result = []
        for text in texts:
            result.append(
                [
                    float("塔吊" in text),
                    float("制动器" in text),
                    float("库存" in text),
                ]
            )
        return result


@pytest.mark.asyncio
async def test_hybrid_rag_keeps_project_isolation_and_citations():
    index = HybridRagIndex()
    embedder = FakeEmbedder()
    await index.ingest(
        "project-a", "doc-a", [ParsedSection("page:12", "塔吊月检必须检查限位器和制动器")], embedder
    )
    await index.ingest(
        "project-b", "doc-b", [ParsedSection("page:3", "塔吊月检可跳过制动器")], embedder
    )
    hits = await index.search("project-a", "塔吊制动器要求", embedder)
    assert [hit.document_id for hit in hits] == ["doc-a"]
    assert hits[0].location == "page:12"
    assert "制动器" in hits[0].quote
