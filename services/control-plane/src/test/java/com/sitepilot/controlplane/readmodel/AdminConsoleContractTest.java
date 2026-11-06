package com.sitepilot.controlplane.readmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitepilot.controlplane.context.RequestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminConsoleContractTest {
    @Test
    void staticConsoleContainsRiskAndContractPlatformViews() throws Exception {
        String html = new String(getClass().getResourceAsStream("/static/index.html").readAllBytes(), StandardCharsets.UTF_8);
        String js = new String(getClass().getResourceAsStream("/static/app.js").readAllBytes(), StandardCharsets.UTF_8);
        String css = new String(getClass().getResourceAsStream("/static/styles.css").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("risk-list", "investigation-graph", "evidence", "timeline",
                "pending-approval", "final-result", "pt-plan", "pt-result", "create-task-form");
        assertThat(js).contains("/api/v1/read/risks", "/task-graph", "/audit-timeline", "/approval",
                "/api/v1/platform/tasks", "/execute", "/artifact", "plan.tasks", "plan.levels");
        assertThat(js).doesNotContain("method: 'PUT'", "method: 'DELETE'");
        assertThat(js).contains("Last-Event-ID", "X-SitePilot-Tenant", "X-SitePilot-Project",
                "X-SitePilot-Actor", "X-Correlation-ID");
        assertThat(css).contains("@media (max-width:960px)", "@media (max-width:560px)",
                "@media (prefers-reduced-motion:reduce)");
        assertThat(Files.readString(Path.of("..", "..", "apps", "admin-console", "index.html"))).isEqualTo(html);
        assertThat(Files.readString(Path.of("..", "..", "apps", "admin-console", "app.js"))).isEqualTo(js);
        assertThat(Files.readString(Path.of("..", "..", "apps", "admin-console", "styles.css"))).isEqualTo(css);
        String controller = Files.readString(Path.of("src/main/java/com/sitepilot/controlplane/readmodel/ReadModelController.java"));
        assertThat(controller).contains("NamespaceParameters", "Last-Event-ID", ".comment(\"heartbeat\")");
    }

    @Test
    void streamNamespaceIsExplicitAndIsolated() {
        var namespace = new ReadModelController.NamespaceParameters("tenant-a", "project-a");
        assertThat(namespace.matches(new RequestContext("tenant-a", "project-a", "actor", "corr"))).isTrue();
        assertThat(namespace.matches(new RequestContext("tenant-a", "project-b", "actor", "corr"))).isFalse();
    }
}
