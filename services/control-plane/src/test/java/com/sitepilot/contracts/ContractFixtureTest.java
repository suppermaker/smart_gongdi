package com.sitepilot.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ContractFixtureTest {
    private static final Path REPOSITORY_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path CONTRACTS = REPOSITORY_ROOT.resolve("contracts");
    private static final Path SCENARIOS = REPOSITORY_ROOT.resolve("services/scenario-gym/scenarios");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void parsesCoreExamplesIntoTypedRecords() throws IOException {
        WorksiteEvent event = read("event", WorksiteEvent.class);
        InvestigationTask task = read("investigation-task", InvestigationTask.class);
        AgentDelegation delegation = read("delegation", AgentDelegation.class);
        InvestigationRoutingDecision routing = read("investigation-routing", InvestigationRoutingDecision.class);
        ContextPack context = read("context-pack", ContextPack.class);

        assertEquals("1.0", event.schemaVersion());
        assertEquals("crane.03", event.subject().id());
        assertEquals("task.crane-offline.001", task.taskId());
        assertEquals(4, task.budgets().maxSubAgents());
        assertEquals("investigator", delegation.role());
        assertFalse(delegation.allowedTools().isEmpty());
        assertEquals("AUTO_ROUTE", routing.routeDisposition());
        assertEquals(3, routing.capabilities().size());
        assertEquals("investigator", context.role());
        assertEquals(4, context.graphRevision());
    }

    @Test
    void parsesEveryScenarioEventAndToolResult() throws IOException {
        try (Stream<Path> events = Files.list(SCENARIOS)) {
            for (Path scenario : events.filter(Files::isDirectory).toList()) {
                WorksiteEvent event = mapper.readValue(
                        scenario.resolve("events/initial.json").toFile(),
                        WorksiteEvent.class);
                assertEquals("1.0", event.schemaVersion());

                JsonNode tools = mapper.readTree(scenario.resolve("tools.yaml").toFile());
                for (JsonNode tool : tools.path("tools")) {
                    for (JsonNode response : tool.path("responses")) {
                        ToolResult result = mapper.treeToValue(response.path("result"), ToolResult.class);
                        assertEquals(tool.path("name").asText(), result.toolName());
                    }
                }
            }
        }
    }

    @Test
    void publicScenarioFilesDoNotExposeHiddenTruthMarkers() throws IOException {
        try (Stream<Path> files = Files.walk(SCENARIOS)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (path.toString().contains("/private/")) {
                    continue;
                }
                String text = Files.readString(path).toLowerCase();
                assertFalse(text.contains("root_causes"), path.toString());
                assertFalse(text.contains("ground_truth"), path.toString());
            }
        }
    }

    private <T> T read(String contract, Class<T> type) throws IOException {
        return mapper.readValue(CONTRACTS.resolve(contract + "/examples/valid.json").toFile(), type);
    }
}
