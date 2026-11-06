package com.sitepilot.controlplane.action;

import java.util.Map;

/** Integration boundary only; SitePilot does not implement a report engine. */
public interface ReportGenerationPort {
    ReportReference requestReport(String taskId, String actionId, Map<String, Object> closureFacts);
    record ReportReference(String reportId, String status) {}
}
