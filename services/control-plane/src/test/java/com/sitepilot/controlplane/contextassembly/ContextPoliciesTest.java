package com.sitepilot.controlplane.contextassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitepilot.controlplane.error.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextPoliciesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void phaseResolverPrioritizesCriticalConflictAndClosure() throws Exception {
        ContextPhaseResolver resolver = new ContextPhaseResolver();
        assertThat(resolver.resolve("verifier", "closure",
                List.of(json.readTree("{\"severity\":\"critical\"}")), List.of()))
                .isEqualTo(ContextPhaseResolver.ContextPhase.CONFLICT_RESOLUTION);
        assertThat(resolver.resolve("verifier", "closure", List.of(), List.of()))
                .isEqualTo(ContextPhaseResolver.ContextPhase.CLOSURE);
        assertThat(resolver.resolve("verifier", "conclusion", List.of(), List.of()))
                .isEqualTo(ContextPhaseResolver.ContextPhase.CONCLUSION);
    }

    @Test
    void budgetOrdersHardPriorityBeforeSoftScoreAndFailsClosed() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(json);
        var support = new ContextBudgetPolicy.RankedEvidence(
                json.createObjectNode().put("id", "support"), 1.0, false, 4,
                ContextBudgetPolicy.Partition.SUPPORTING_EVIDENCE);
        var counter = new ContextBudgetPolicy.RankedEvidence(
                json.createObjectNode().put("id", "counter"), 0.1, true, 1,
                ContextBudgetPolicy.Partition.COUNTEREVIDENCE);
        var result = policy.apply(List.of(support, counter), 10, 100);
        assertThat(result.selected().get(0).card().path("id").asText()).isEqualTo("counter");
        assertThat(result.allocation().get(ContextBudgetPolicy.Partition.COUNTEREVIDENCE))
                .isGreaterThan(0);

        assertThatThrownBy(() -> policy.apply(List.of(counter), 100, 20))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTEXT_BUDGET_INSUFFICIENT");
                    assertThat(error.getMessage()).contains("partitions=");
                });
    }

    @Test
    void deltaPolicyBuildsLineageAndCheckpointsAfterThreeDeltas() {
        ContextDeltaPolicy policy = new ContextDeltaPolicy(json);
        ObjectNode full = pack("node-a", "evidence-a");
        var first = policy.apply(full, null, Set.of());
        assertThat(first.mode()).isEqualTo("FULL");
        assertThat(full.path("parent_context").isNull()).isTrue();

        ContextSnapshotEntity parent = snapshot("ctx-parent", "sha256:" + "a".repeat(64),
                1, full.toString(), 0);
        ObjectNode next = pack("node-a", "evidence-b");
        var delta = policy.apply(next, parent, Set.of("expired-a"));
        assertThat(delta.mode()).isEqualTo("DELTA");
        assertThat(delta.chainDepth()).isEqualTo(1);
        assertThat(next.path("parent_context").path("context_id").asText()).isEqualTo("ctx-parent");
        assertThat(next.path("delta").path("added_node_ids").toString()).contains("evidence-b");
        assertThat(next.path("delta").path("removed_node_ids").toString()).contains("evidence-a");

        ContextSnapshotEntity deepParent = snapshot("ctx-deep", "sha256:" + "b".repeat(64),
                4, next.toString(), 3);
        ObjectNode checkpoint = pack("node-a", "evidence-c");
        assertThat(policy.apply(checkpoint, deepParent, Set.of()).mode()).isEqualTo("FULL");
        assertThat(checkpoint.path("parent_context").isNull()).isTrue();
    }

    private ObjectNode pack(String hypothesisId, String evidenceId) {
        ObjectNode pack = json.createObjectNode();
        ObjectNode manifest = pack.putObject("manifest");
        manifest.putArray("included_node_ids").add(hypothesisId).add(evidenceId);
        pack.putArray("evidence_cards").addObject()
                .put("evidence_id", evidenceId).put("claim", evidenceId);
        pack.putArray("evidence_handles").addObject()
                .put("evidence_id", evidenceId).put("detail_tool", "get_evidence_detail");
        return pack;
    }

    private ContextSnapshotEntity snapshot(String id, String digest, int revision,
                                           String payload, int depth) {
        return new ContextSnapshotEntity(
                "tenant-a", "project-a", "task-a", "agent-a", id, revision,
                "investigator", null, "context-policy.v2", "investigator.v2",
                "sha256:" + "c".repeat(64), digest, payload, "{}", 100, "READY",
                depth == 0 ? "FULL" : "DELTA", depth == 0 ? null : "parent",
                depth == 0 ? null : "sha256:" + "d".repeat(64),
                depth == 0 ? null : revision - 1, depth);
    }
}
