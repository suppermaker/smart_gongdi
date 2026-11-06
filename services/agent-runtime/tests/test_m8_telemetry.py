from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from sitepilot_agent_runtime.telemetry import AgentTelemetry, current_trace_headers


def test_agent_span_chain_and_sensitive_attributes_are_absent() -> None:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    telemetry = AgentTelemetry(provider.get_tracer("test"))

    with telemetry.span("agent", task_id="task-1", stage="agent", correlation_id="corr-1"):
        with telemetry.span("mcp-tool", task_id="task-1", stage="tool", tool="graph_read"):
            with telemetry.span(
                "evidence-approval-action-closure", task_id="task-1", stage="closure"
            ):
                pass

    spans = exporter.get_finished_spans()
    assert len(spans) == 3
    assert len({span.context.trace_id for span in spans}) == 1
    for span in spans:
        keys = {key for key in span.attributes}
        assert not any(
            word in key.lower()
            for key in keys
            for word in ("token", "secret", "password", "payload")
        )
        assert "sitepilot.task_id" in keys


def test_active_trace_context_is_serialized_for_mcp_http() -> None:
    provider = TracerProvider()
    telemetry = AgentTelemetry(provider.get_tracer("propagation-test"))
    with telemetry.span("outbound", task_id="task-1", stage="tool"):
        headers = current_trace_headers()
    assert headers["traceparent"].startswith("00-")
    assert len(headers["traceparent"].split("-")) == 4
