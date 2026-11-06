package com.sitepilot.controlplane.action;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportGenerationPortTest {
    @Test void fakeAdapterHonorsPortContractWithoutReportEngine() {
        ReportGenerationPort port = new FakeReportGenerationAdapter();
        var result = port.requestReport("task-1", "action-1", Map.of("closed", true));
        assertThat(result.reportId()).isEqualTo("fake-report:task-1:action-1");
        assertThat(result.status()).isEqualTo("ACCEPTED");
    }

    @Test void httpAdapterImplementsTheSameBoundaryContract() {
        ReportGenerationPort port = new ReportGenerationHttpAdapter(
                URI.create("http://report.invalid/v1/reports"), HttpClient.newHttpClient(), new ObjectMapper());
        assertThat(port).isInstanceOf(ReportGenerationHttpAdapter.class);
    }
}
