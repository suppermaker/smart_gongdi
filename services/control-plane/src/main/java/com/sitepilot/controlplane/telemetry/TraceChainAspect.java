package com.sitepilot.controlplane.telemetry;

import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.mcp.McpPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TraceChainAspect {
    private final SitePilotTelemetry telemetry;
    private final MeterRegistry metrics;

    public TraceChainAspect(SitePilotTelemetry telemetry, MeterRegistry metrics) {
        this.telemetry = telemetry;
        this.metrics = metrics;
    }

    @Around("execution(public * com.sitepilot.controlplane.service.ControlPlaneService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.graph.GraphMergeService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.action.ActionService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.mcp.McpToolService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.platform.PlatformTaskService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.platform.PlatformExecutionService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.platform.ConversationService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.knowledge.KnowledgeService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.databaseqa.DatabaseQaService.*(..)) || "
            + "execution(public * com.sitepilot.controlplane.application.ContractApplicationService.*(..))")
    public Object trace(ProceedingJoinPoint joinPoint) {
        RequestContext context = context(joinPoint.getArgs());
        String stage = joinPoint.getTarget().getClass().getSimpleName();
        String operation = joinPoint.getSignature().getName();
        String name = "sitepilot." + stage + "." + operation;
        Timer.Sample sample = Timer.start(metrics);
        try {
            Object result = telemetry.inSpan(name, context, stage, () -> proceed(joinPoint));
            metrics.counter("sitepilot.platform.operations", "stage", stage,
                    "operation", operation, "outcome", "success").increment();
            return result;
        } catch (RuntimeException exception) {
            metrics.counter("sitepilot.platform.operations", "stage", stage,
                    "operation", operation, "outcome", "error").increment();
            throw exception;
        } finally {
            sample.stop(metrics.timer("sitepilot.platform.operation.duration",
                    "stage", stage, "operation", operation));
        }
    }

    private RequestContext context(Object[] arguments) {
        return Arrays.stream(arguments).filter(RequestContext.class::isInstance)
                .map(RequestContext.class::cast).findFirst()
                .orElseGet(() -> Arrays.stream(arguments).filter(McpPrincipal.class::isInstance)
                        .map(McpPrincipal.class::cast)
                        .map(p -> new RequestContext(p.tenantId(), p.projectId(), p.actorId(), "mcp:" + p.taskId()))
                        .findFirst().orElseGet(RequestContextHolder::get));
    }

    private Object proceed(ProceedingJoinPoint joinPoint) {
        try { return joinPoint.proceed(); }
        catch (RuntimeException exception) { throw exception; }
        catch (Throwable throwable) { throw new IllegalStateException(throwable); }
    }
}
