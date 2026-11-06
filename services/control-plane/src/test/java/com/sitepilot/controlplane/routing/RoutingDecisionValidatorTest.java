package com.sitepilot.controlplane.routing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitepilot.controlplane.error.ApiException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RoutingDecisionValidatorTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();

    @Test
    void acceptsVersionedKnownCapabilitiesAndRejectsInvalidInput() throws Exception {
        var value = json.readTree(Path.of("..", "..", "contracts", "investigation-routing",
                "examples", "valid.json").toFile());
        assertThatCode(() -> validator.validate(value, "task.crane.001", 0))
                .doesNotThrowAnyException();

        ObjectNode invalidCapability = value.deepCopy();
        ((ObjectNode) invalidCapability.withArray("capabilities").get(0))
                .put("capability", "model_chosen_arbitrary_tool");
        assertThatThrownBy(() -> validator.validate(invalidCapability, "task.crane.001", 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unknown capability");

        ObjectNode invalidProbability = value.deepCopy();
        invalidProbability.put("unknown_probability", 1.1);
        assertThatThrownBy(() -> validator.validate(invalidProbability, "task.crane.001", 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid probability");
    }
}
