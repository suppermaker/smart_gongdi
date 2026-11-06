package com.sitepilot.controlplane.contextassembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.error.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ContextBudgetPolicy {
    private final ObjectMapper json;

    public ContextBudgetPolicy(ObjectMapper json) { this.json = json; }

    public BudgetResult apply(List<RankedEvidence> evidence, int baseTokens, int maxTokens) {
        List<RankedEvidence> mandatory = evidence.stream()
                .filter(RankedEvidence::mandatory)
                .sorted(order())
                .toList();
        int mandatoryEvidenceTokens = tokens(mandatory);
        int mandatoryTokens = baseTokens + mandatoryEvidenceTokens;
        if (mandatoryTokens > maxTokens) {
            throw new ApiException("CONTEXT_BUDGET_INSUFFICIENT", 409,
                    "mandatory context exceeds token budget: base=" + baseTokens
                            + ", mandatory_evidence=" + mandatoryEvidenceTokens
                            + ", max=" + maxTokens + ", partitions=" + allocation(mandatory));
        }
        List<RankedEvidence> selected = new ArrayList<>(mandatory);
        int used = mandatoryTokens;
        List<RankedEvidence> optional = evidence.stream()
                .filter(item -> !item.mandatory())
                .sorted(order())
                .toList();
        int trimmed = 0;
        for (RankedEvidence item : optional) {
            int itemTokens = tokens(item.card());
            if (used + itemTokens <= maxTokens) {
                selected.add(item);
                used += itemTokens;
            } else {
                trimmed++;
            }
        }
        return new BudgetResult(selected, used, trimmed, allocation(selected));
    }

    public int tokens(Object value) {
        try {
            return Math.max(1, json.writeValueAsString(value).length() / 4);
        } catch (Exception exception) {
            throw new IllegalArgumentException("context cannot be estimated", exception);
        }
    }

    private Comparator<RankedEvidence> order() {
        return Comparator.comparingInt(RankedEvidence::priority)
                .thenComparing(Comparator.comparingDouble(RankedEvidence::score).reversed());
    }

    private int tokens(List<RankedEvidence> values) {
        return values.stream().mapToInt(item -> tokens(item.card())).sum();
    }

    private Map<Partition, Integer> allocation(List<RankedEvidence> values) {
        Map<Partition, Integer> result = new EnumMap<>(Partition.class);
        for (Partition partition : Partition.values()) result.put(partition, 0);
        for (RankedEvidence item : values) {
            result.compute(item.partition(), (ignored, current) -> current + tokens(item.card()));
        }
        return result;
    }

    public enum Partition {
        COUNTEREVIDENCE,
        SUPPORTING_EVIDENCE,
        BACKGROUND
    }

    public record RankedEvidence(JsonNode card, double score, boolean mandatory,
                                 int priority, Partition partition) {
        public RankedEvidence(JsonNode card, double score, boolean mandatory) {
            this(card, score, mandatory, mandatory ? 1 : 5, Partition.BACKGROUND);
        }
    }

    public record BudgetResult(List<RankedEvidence> selected, int estimatedTokens,
                               int trimmed, Map<Partition, Integer> allocation) {}
}
