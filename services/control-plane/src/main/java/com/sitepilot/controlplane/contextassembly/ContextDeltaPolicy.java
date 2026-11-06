package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ContextDeltaPolicy {
    private static final int MAX_DELTA_CHAIN = 3;
    private final ObjectMapper json;

    public ContextDeltaPolicy(ObjectMapper json) {
        this.json = json;
    }

    public DeltaDecision apply(ObjectNode pack, ContextSnapshotEntity parent,
                               Set<String> expiredNodeIds) {
        ObjectNode delta = pack.putObject("delta");
        if (parent == null || parent.getChainDepth() >= MAX_DELTA_CHAIN) {
            pack.put("context_mode", "FULL");
            pack.putNull("parent_context");
            copy(delta.putArray("added_node_ids"), ids(pack));
            delta.putArray("updated_node_ids");
            delta.putArray("removed_node_ids");
            copy(delta.putArray("expired_node_ids"), expiredNodeIds);
            return new DeltaDecision("FULL", null, 0);
        }

        JsonNode previous = read(parent.getContextPayload());
        Set<String> currentIds = ids(pack);
        Set<String> previousIds = ids(previous);
        Set<String> added = difference(currentIds, previousIds);
        Set<String> removed = difference(previousIds, currentIds);
        Set<String> updated = updatedEvidence(previous, pack, currentIds, previousIds);

        pack.put("context_mode", "DELTA");
        ObjectNode parentContext = pack.putObject("parent_context");
        parentContext.put("context_id", parent.getContextId());
        parentContext.put("context_digest", parent.getContextDigest());
        parentContext.put("graph_revision", parent.getGraphRevision());
        copy(delta.putArray("added_node_ids"), added);
        copy(delta.putArray("updated_node_ids"), updated);
        copy(delta.putArray("removed_node_ids"), removed);
        copy(delta.putArray("expired_node_ids"), expiredNodeIds);
        retainChangedEvidence(pack, union(added, updated));
        return new DeltaDecision("DELTA", parent, parent.getChainDepth() + 1);
    }

    private Set<String> ids(JsonNode pack) {
        Set<String> result = new HashSet<>();
        pack.path("manifest").path("included_node_ids").forEach(item -> result.add(item.asText()));
        return result;
    }

    private Set<String> updatedEvidence(JsonNode previous, JsonNode current,
                                        Set<String> currentIds, Set<String> previousIds) {
        Set<String> shared = new HashSet<>(currentIds);
        shared.retainAll(previousIds);
        var oldCards = cards(previous);
        var newCards = cards(current);
        Set<String> result = new HashSet<>();
        for (String id : shared) {
            JsonNode oldCard = oldCards.path(id);
            JsonNode newCard = newCards.path(id);
            if (!oldCard.isMissingNode() && !newCard.isMissingNode() && !oldCard.equals(newCard)) {
                result.add(id);
            }
        }
        return result;
    }

    private ObjectNode cards(JsonNode pack) {
        ObjectNode result = json.createObjectNode();
        pack.path("evidence_cards").forEach(card ->
                result.set(card.path("evidence_id").asText(), card));
        return result;
    }

    private void retainChangedEvidence(ObjectNode pack, Set<String> changed) {
        ArrayNode evidence = json.createArrayNode();
        pack.path("evidence_cards").forEach(card -> {
            if (changed.contains(card.path("evidence_id").asText())) evidence.add(card);
        });
        ArrayNode handles = json.createArrayNode();
        pack.path("evidence_handles").forEach(handle -> {
            if (changed.contains(handle.path("evidence_id").asText())) handles.add(handle);
        });
        pack.set("evidence_cards", evidence);
        pack.set("evidence_handles", handles);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("stored context JSON is invalid", exception);
        }
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private void copy(ArrayNode target, Set<String> values) {
        values.stream().sorted().forEach(target::add);
    }

    public record DeltaDecision(String mode, ContextSnapshotEntity parent, int chainDepth) {}
}
