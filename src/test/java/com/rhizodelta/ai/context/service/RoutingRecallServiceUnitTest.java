package com.rhizodelta.ai.context.service;

import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.core.service.GraphRootLocatorService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoutingRecallServiceUnitTest {

    @Test
    void shouldResolveRootIdAndApplyRootScopedSearch() {
        EmbeddingModelService embeddingModelService = mock(EmbeddingModelService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        GraphRootLocatorService graphRootLocatorService = mock(GraphRootLocatorService.class);
        ContextPruner contextPruner = mock(ContextPruner.class);
        RoutingRecallService service = new RoutingRecallService(
                embeddingModelService,
                embeddingService,
                graphRootLocatorService,
                contextPruner
        );
        UUID candidateNodeId = UUID.randomUUID();
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f);
        List<SimilaritySearchResult> candidates = List.of(
                new SimilaritySearchResult(candidateNodeId, "Human_Post", 0.95d, "candidate", Instant.now(), List.of())
        );
        PrunedContext prunedContext = new PrunedContext(candidates, false, 0);
        when(embeddingModelService.embed("post content")).thenReturn(vector);
        when(graphRootLocatorService.resolveRootId("target-1")).thenReturn("root-1");
        when(embeddingService.searchSimilar(vector, null, "root-1")).thenReturn(candidates);
        when(contextPruner.prune(candidates, "target-1")).thenReturn(prunedContext);

        PrunedContext result = service.recall("post content", "target-1");

        assertThat(result).isEqualTo(prunedContext);
        verify(graphRootLocatorService).resolveRootId("target-1");
        verify(embeddingService).searchSimilar(vector, null, "root-1");
        verify(contextPruner).prune(candidates, "target-1");
    }

    /**
     * L3 防御：targetNodeId 为空时直接返回空 PrunedContext，不发起 embedding API
     * 调用、不做向量搜索、不查 root 解析。这样即便 L0 上游被绕过，根帖也不会
     * 退化为对全索引的 top-K 扫描。
     */
    @Test
    void shouldReturnEmptyAndCallNoCollaboratorsForBlankTargetNodeId() {
        EmbeddingModelService embeddingModelService = mock(EmbeddingModelService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        GraphRootLocatorService graphRootLocatorService = mock(GraphRootLocatorService.class);
        ContextPruner contextPruner = mock(ContextPruner.class);
        RoutingRecallService service = new RoutingRecallService(
                embeddingModelService,
                embeddingService,
                graphRootLocatorService,
                contextPruner
        );

        PrunedContext nullTarget = service.recall("any content", null);
        PrunedContext blankTarget = service.recall("any content", "");
        PrunedContext whitespace = service.recall("any content", "   ");

        assertThat(nullTarget.selected()).isEmpty();
        assertThat(blankTarget.selected()).isEmpty();
        assertThat(whitespace.selected()).isEmpty();

        verifyNoInteractions(embeddingModelService);
        verifyNoInteractions(embeddingService);
        verifyNoInteractions(graphRootLocatorService);
        verifyNoInteractions(contextPruner);
    }
}
