package com.sitepilot.controlplane.service;

import com.sitepilot.controlplane.api.Requests.IngestEvent;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.domain.OutboxEventEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.domain.SourceEventEntity;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import com.sitepilot.controlplane.repository.OutboxEventRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import com.sitepilot.controlplane.repository.SourceEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventIngestionTransaction {
    private final SourceEventRepository events;
    private final RiskRepository risks;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final Function<Object, String> json;

    EventIngestionTransaction(SourceEventRepository events, RiskRepository risks,
                              AuditLogRepository audits, OutboxEventRepository outbox,
                              JsonWriter jsonWriter) {
        this.events = events;
        this.risks = risks;
        this.audits = audits;
        this.outbox = outbox;
        this.json = jsonWriter::write;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RiskEntity create(RequestContext context, IngestEvent request) {
        events.saveAndFlush(new SourceEventEntity(
                context.tenantId(), context.projectId(), request.eventId(), request.eventType(),
                request.occurredAt(), json.apply(request.attributes())));
        String riskId = "risk-" + UUID.nameUUIDFromBytes(
                (context.tenantId() + ":" + context.projectId() + ":" + request.eventId())
                        .getBytes(StandardCharsets.UTF_8));
        RiskEntity risk = risks.save(new RiskEntity(
                context.tenantId(), context.projectId(), riskId, request.eventId(), request.title()));
        audits.save(new AuditLogEntity(
                context.tenantId(), context.projectId(), context.actor(), "RISK_DETECTED",
                "risk", riskId, "{}"));
        outbox.save(new OutboxEventEntity(
                context.tenantId(), context.projectId(), "risk-detected:" + request.eventId(),
                "risk", riskId, "risk.detected",
                json.apply(Map.of("riskId", riskId, "sourceEventId", request.eventId()))));
        return risk;
    }
}
