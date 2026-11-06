"""Fail-closed guard for model-proposed read-only SQL."""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class SqlPolicy:
    allowed_tables: frozenset[str]
    project_column: str = "project_id"
    max_rows: int = 200


class ReadOnlySqlGuard:
    FORBIDDEN = re.compile(
        r"\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|call|execute|merge)\b",
        re.IGNORECASE,
    )

    def validate_and_scope(self, sql: str, project_id: str, policy: SqlPolicy) -> str:
        normalized = " ".join(sql.strip().split())
        if not normalized.lower().startswith("select "):
            raise SqlPolicyError("SQL_READ_ONLY_REQUIRED")
        if ";" in normalized.rstrip(";") or self.FORBIDDEN.search(normalized):
            raise SqlPolicyError("SQL_STATEMENT_FORBIDDEN")
        tables = {
            match.group(1).lower()
            for match in re.finditer(
                r"\b(?:from|join)\s+([a-zA-Z_][\w.]*)", normalized, re.IGNORECASE
            )
        }
        if not tables or not tables <= policy.allowed_tables:
            raise SqlPolicyError("SQL_TABLE_NOT_ALLOWED")
        escaped_project = project_id.replace("'", "''")
        predicate = f"{policy.project_column} = '{escaped_project}'"
        lower = normalized.lower()
        limit_match = re.search(r"\blimit\s+(\d+)\b", lower)
        if " where " in lower:
            normalized = re.sub(
                r"\bwhere\b", f"WHERE {predicate} AND ", normalized, count=1, flags=re.IGNORECASE
            )
        else:
            insert_at = len(normalized)
            for keyword in (" group by ", " order by ", " limit "):
                index = lower.find(keyword)
                if index >= 0:
                    insert_at = min(insert_at, index)
            normalized = normalized[:insert_at] + f" WHERE {predicate}" + normalized[insert_at:]
        if limit_match:
            requested = int(limit_match.group(1))
            if requested > policy.max_rows:
                normalized = re.sub(
                    r"\blimit\s+\d+\b", f"LIMIT {policy.max_rows}", normalized, flags=re.IGNORECASE
                )
        else:
            normalized += f" LIMIT {policy.max_rows}"
        return normalized


class SqlPolicyError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)
