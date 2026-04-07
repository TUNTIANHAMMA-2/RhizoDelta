package com.rhizodelta.ai.context.service;

import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ContextPruner {
    private final int topN;

    public ContextPruner(@Value("${rhizodelta.ai.rerank.top-n:3}") int topN) {
        this.topN = topN;
    }

    public PrunedContext prune(List<SimilaritySearchResult> candidates, String targetNodeId) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            return new PrunedContext(List.of(), false, 0);
        }
        UUID targetUuid = parseTargetNodeId(targetNodeId);
        List<SimilaritySearchResult> sorted = candidates.stream()
                .sorted(Comparator.comparing(SimilaritySearchResult::score, Comparator.nullsLast(Double::compareTo)).reversed())
                .toList();
        SimilaritySearchResult targetCandidate = findTargetCandidate(sorted, targetUuid);
        List<SimilaritySearchResult> selected = new ArrayList<>();
        if (targetCandidate != null) {
            selected.add(targetCandidate);
        }
        for (SimilaritySearchResult candidate : sorted) {
            if (selected.size() >= topN) {
                break;
            }
            if (targetCandidate != null && candidate.node_id().equals(targetCandidate.node_id())) {
                continue;
            }
            selected.add(candidate);
        }
        return new PrunedContext(
                selected,
                targetCandidate != null && !selected.isEmpty() && selected.get(0).node_id().equals(targetCandidate.node_id()),
                Math.max(candidates.size() - selected.size(), 0)
        );
    }

    private SimilaritySearchResult findTargetCandidate(List<SimilaritySearchResult> candidates, UUID targetUuid) {
        if (targetUuid == null) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> targetUuid.equals(candidate.node_id()))
                .findFirst()
                .orElse(null);
    }

    private UUID parseTargetNodeId(String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return null;
        }
        return UUID.fromString(targetNodeId);
    }
}
