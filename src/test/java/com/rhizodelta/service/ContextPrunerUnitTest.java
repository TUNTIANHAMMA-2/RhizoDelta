package com.rhizodelta.service;

import com.rhizodelta.domain.embedding.PrunedContext;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPrunerUnitTest {

    @Test
    void shouldPromoteExplicitTargetIntoSelectedContext() {
        UUID targetId = UUID.randomUUID();
        SimilaritySearchResult highScore = result(UUID.randomUUID(), 0.99d);
        SimilaritySearchResult mediumScore = result(UUID.randomUUID(), 0.80d);
        SimilaritySearchResult target = result(targetId, 0.20d);
        ContextPruner pruner = new ContextPruner(2);

        PrunedContext prunedContext = pruner.prune(List.of(highScore, mediumScore, target), targetId.toString());

        assertThat(prunedContext.targetPromoted()).isTrue();
        assertThat(prunedContext.selected()).extracting(SimilaritySearchResult::node_id)
                .containsExactly(targetId, highScore.node_id());
        assertThat(prunedContext.droppedCount()).isEqualTo(1);
    }

    @Test
    void shouldKeepHighestScoringCandidatesWhenNoExplicitTargetProvided() {
        SimilaritySearchResult top = result(UUID.randomUUID(), 0.95d);
        SimilaritySearchResult second = result(UUID.randomUUID(), 0.90d);
        SimilaritySearchResult third = result(UUID.randomUUID(), 0.10d);
        ContextPruner pruner = new ContextPruner(2);

        PrunedContext prunedContext = pruner.prune(List.of(third, second, top), null);

        assertThat(prunedContext.targetPromoted()).isFalse();
        assertThat(prunedContext.selected()).extracting(SimilaritySearchResult::node_id)
                .containsExactly(top.node_id(), second.node_id());
        assertThat(prunedContext.droppedCount()).isEqualTo(1);
    }

    private static SimilaritySearchResult result(UUID nodeId, double score) {
        return new SimilaritySearchResult(nodeId, "Human_Post", score, "content", Instant.now(), List.of());
    }
}
