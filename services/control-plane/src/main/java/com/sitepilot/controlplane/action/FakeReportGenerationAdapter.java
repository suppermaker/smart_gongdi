package com.sitepilot.controlplane.action;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class FakeReportGenerationAdapter implements ReportGenerationPort {
    @Override
    public ReportReference requestReport(String taskId, String actionId, Map<String, Object> closureFacts) {
        return new ReportReference("fake-report:" + taskId + ":" + actionId, "ACCEPTED");
    }
}
