"""OpenTelemetry helpers with a strict safe-attribute allowlist."""

from __future__ import annotations

import os
from collections.abc import Iterator
from contextlib import contextmanager

from opentelemetry import context, propagate, trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

_SAFE_KEYS = {"sitepilot.task_id", "sitepilot.correlation_id", "sitepilot.stage", "sitepilot.tool"}


class AgentTelemetry:
    def __init__(self, tracer: trace.Tracer | None = None) -> None:
        self.tracer = tracer or trace.get_tracer("sitepilot.agent-runtime", "2.0")

    @contextmanager
    def span(
        self,
        name: str,
        *,
        task_id: str,
        stage: str,
        correlation_id: str | None = None,
        traceparent: str | None = None,
        tool: str | None = None,
    ) -> Iterator[trace.Span]:
        parent = (
            propagate.extract({"traceparent": traceparent})
            if traceparent
            else context.get_current()
        )
        attributes = {
            "sitepilot.task_id": task_id,
            "sitepilot.correlation_id": correlation_id or task_id,
            "sitepilot.stage": stage,
        }
        if tool:
            attributes["sitepilot.tool"] = tool
        if set(attributes) - _SAFE_KEYS:
            raise ValueError("unsafe telemetry attribute")
        with self.tracer.start_as_current_span(
            name, context=parent, attributes=attributes
        ) as current:
            yield current


def current_trace_headers() -> dict[str, str]:
    """Serialize the active W3C trace context for outbound HTTP transports."""
    carrier: dict[str, str] = {}
    propagate.inject(carrier)
    return carrier


def configure_telemetry() -> TracerProvider:
    provider = TracerProvider(resource=Resource.create({"service.name": "sitepilot-agent-runtime"}))
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
    if endpoint and os.getenv("SITEPILOT_OTEL_EXPORT", "true").lower() == "true":
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    trace.set_tracer_provider(provider)
    return provider
