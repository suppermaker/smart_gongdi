"""Typed ports for review, report and OCR specialist agents."""

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class ReviewFinding:
    finding_id: str
    severity: str
    document_location: dict[str, Any]
    problem: str
    standard_ref: str
    quote: str
    suggestion: str


@dataclass(frozen=True)
class ReportRequest:
    template_id: str
    output_format: str
    knowledge_refs: tuple[str, ...] = ()
    query_refs: tuple[str, ...] = ()


@dataclass(frozen=True)
class OcrField:
    name: str
    value: str
    confidence: float
    page: int | None = None
    bbox: tuple[int, int, int, int] | None = None


class DocumentReviewPort(Protocol):
    async def review(self, document_ref: str, template_id: str) -> list[ReviewFinding]: ...


class ReportGenerationPort(Protocol):
    async def generate(self, request: ReportRequest) -> str: ...


class OcrExtractionPort(Protocol):
    async def extract(self, object_ref: str, schema: dict[str, Any]) -> list[OcrField]: ...
