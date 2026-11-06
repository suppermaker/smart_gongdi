"""Multi-format parsing, chunking and hybrid retrieval for the Knowledge Agent."""

from __future__ import annotations

import asyncio
import json
import math
import re
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from xml.etree import ElementTree as ET


@dataclass(frozen=True)
class ParsedSection:
    location: str
    text: str


@dataclass(frozen=True)
class RagChunk:
    chunk_id: str
    document_id: str
    project_id: str
    location: str
    text: str
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class RagHit:
    chunk_id: str
    document_id: str
    location: str
    quote: str
    score: float


class Embedder(Protocol):
    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class OpenAIEmbeddingClient:
    def __init__(self, base_url: str, model: str) -> None:
        self.url = base_url.rstrip("/") + "/v1/embeddings"
        self.model = model

    async def embed(self, texts: list[str]) -> list[list[float]]:
        body = json.dumps({"model": self.model, "input": texts}).encode()
        request = urllib.request.Request(self.url, body, {"Content-Type": "application/json"})
        result = await asyncio.to_thread(self._post, request)
        return [
            item["embedding"] for item in sorted(result["data"], key=lambda item: item["index"])
        ]

    @staticmethod
    def _post(request: urllib.request.Request) -> dict:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)


class OfficeDocumentParser:
    A_NS = {"a": "http://schemas.openxmlformats.org/drawingml/2006/main"}
    W_NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    X_NS = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}

    def parse(self, path: Path) -> list[ParsedSection]:
        suffix = path.suffix.lower()
        if suffix in {".txt", ".md", ".csv"}:
            return [ParsedSection("file", path.read_text(encoding="utf-8"))]
        if suffix == ".docx":
            return self._docx(path)
        if suffix == ".pptx":
            return self._pptx(path)
        if suffix == ".xlsx":
            return self._xlsx(path)
        raise ValueError(f"DOCUMENT_FORMAT_UNSUPPORTED: {suffix}")

    def _docx(self, path: Path) -> list[ParsedSection]:
        with zipfile.ZipFile(path) as zf:
            root = ET.fromstring(zf.read("word/document.xml"))
        values = []
        for index, paragraph in enumerate(root.findall(".//w:p", self.W_NS), 1):
            text = "".join(
                item.text or "" for item in paragraph.findall(".//w:t", self.W_NS)
            ).strip()
            if text:
                values.append(ParsedSection(f"paragraph:{index}", text))
        return values

    def _pptx(self, path: Path) -> list[ParsedSection]:
        values = []
        with zipfile.ZipFile(path) as zf:
            names = sorted(
                name for name in zf.namelist() if re.fullmatch(r"ppt/slides/slide\d+\.xml", name)
            )
            for name in names:
                root = ET.fromstring(zf.read(name))
                text = "\n".join(
                    (item.text or "").strip()
                    for item in root.findall(".//a:t", self.A_NS)
                    if (item.text or "").strip()
                )
                if text:
                    values.append(ParsedSection(name.rsplit("/", 1)[-1].replace(".xml", ""), text))
        return values

    def _xlsx(self, path: Path) -> list[ParsedSection]:
        values = []
        with zipfile.ZipFile(path) as zf:
            shared = []
            if "xl/sharedStrings.xml" in zf.namelist():
                root = ET.fromstring(zf.read("xl/sharedStrings.xml"))
                shared = [
                    "".join(item.text or "" for item in node.findall(".//x:t", self.X_NS))
                    for node in root.findall("x:si", self.X_NS)
                ]
            for name in sorted(
                item for item in zf.namelist() if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", item)
            ):
                root = ET.fromstring(zf.read(name))
                rows = []
                for row in root.findall(".//x:row", self.X_NS):
                    cells = []
                    for cell in row.findall("x:c", self.X_NS):
                        value = cell.find("x:v", self.X_NS)
                        text = value.text if value is not None else ""
                        if cell.attrib.get("t") == "s" and text:
                            text = shared[int(text)]
                        cells.append(text)
                    if any(cells):
                        rows.append(" | ".join(cells))
                if rows:
                    values.append(
                        ParsedSection(name.rsplit("/", 1)[-1].replace(".xml", ""), "\n".join(rows))
                    )
        return values


def chunk_sections(
    document_id: str, project_id: str, sections: list[ParsedSection], max_chars: int = 1200
) -> list[tuple[str, str, str]]:
    result = []
    sequence = 0
    for section in sections:
        paragraphs = [item.strip() for item in re.split(r"\n+", section.text) if item.strip()]
        current = ""
        for paragraph in paragraphs:
            if current and len(current) + len(paragraph) + 1 > max_chars:
                sequence += 1
                result.append((f"{document_id}:chunk:{sequence}", section.location, current))
                current = ""
            current = paragraph if not current else current + "\n" + paragraph
        if current:
            sequence += 1
            result.append((f"{document_id}:chunk:{sequence}", section.location, current))
    return result


class HybridRagIndex:
    def __init__(self) -> None:
        self._chunks: list[RagChunk] = []

    async def ingest(
        self, project_id: str, document_id: str, sections: list[ParsedSection], embedder: Embedder
    ) -> int:
        chunks = chunk_sections(document_id, project_id, sections)
        vectors = await embedder.embed([item[2] for item in chunks])
        self._chunks.extend(
            RagChunk(chunk_id, document_id, project_id, location, text, tuple(vector))
            for (chunk_id, location, text), vector in zip(chunks, vectors, strict=True)
        )
        return len(chunks)

    async def search(
        self, project_id: str, query: str, embedder: Embedder, limit: int = 5
    ) -> list[RagHit]:
        query_vector = (await embedder.embed([query]))[0]
        query_terms = terms(query)
        scored = []
        for chunk in self._chunks:
            if chunk.project_id != project_id:
                continue
            lexical = len(query_terms & terms(chunk.text)) / max(1, len(query_terms))
            semantic = cosine(query_vector, chunk.embedding)
            score = 0.4 * lexical + 0.6 * semantic
            scored.append((score, chunk))
        scored.sort(key=lambda item: (-item[0], item[1].chunk_id))
        return [
            RagHit(
                chunk.chunk_id, chunk.document_id, chunk.location, chunk.text[:400], round(score, 6)
            )
            for score, chunk in scored[:limit]
        ]


def terms(value: str) -> set[str]:
    return set(re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]{2}", value.lower()))


def cosine(left, right) -> float:
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    norm = math.sqrt(sum(a * a for a in left)) * math.sqrt(sum(b * b for b in right))
    return dot / norm if norm else 0.0
