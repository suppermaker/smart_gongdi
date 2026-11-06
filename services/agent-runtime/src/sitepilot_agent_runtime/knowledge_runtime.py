"""Project-isolated knowledge retrieval with deterministic citations."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class KnowledgeChunk:
    tenant_id: str
    project_id: str
    document_id: str
    chunk_id: str
    text: str
    source_location: str
    version: str


@dataclass(frozen=True)
class KnowledgeHit:
    chunk_id: str
    document_id: str
    source_location: str
    quote: str
    score: float


class ProjectKnowledgeIndex:
    def __init__(self) -> None:
        self._chunks: list[KnowledgeChunk] = []

    def add(self, chunk: KnowledgeChunk) -> None:
        self._chunks.append(chunk)

    def search(
        self, tenant_id: str, project_id: str, query: str, limit: int = 5
    ) -> list[KnowledgeHit]:
        query_terms = terms(query)
        scored = []
        for chunk in self._chunks:
            if chunk.tenant_id != tenant_id or chunk.project_id != project_id:
                continue
            chunk_terms = terms(chunk.text)
            overlap = len(query_terms & chunk_terms)
            if overlap == 0:
                continue
            score = overlap / math.sqrt(max(1, len(query_terms) * len(chunk_terms)))
            scored.append((score, chunk))
        scored.sort(key=lambda item: (-item[0], item[1].chunk_id))
        return [
            KnowledgeHit(
                chunk.chunk_id,
                chunk.document_id,
                chunk.source_location,
                chunk.text[:300],
                round(score, 6),
            )
            for score, chunk in scored[:limit]
        ]


def terms(value: str) -> set[str]:
    latin = re.findall(r"[a-z0-9_]+", value.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]{2,}", value)
    grams = {word[index : index + 2] for word in chinese for index in range(len(word) - 1)}
    return set(latin) | grams
