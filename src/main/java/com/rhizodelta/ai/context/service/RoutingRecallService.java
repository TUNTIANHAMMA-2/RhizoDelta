package com.rhizodelta.ai.context.service;

import com.rhizodelta.ai.context.domain.embedding.PrunedContext;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.ai.context.service.ContextPruner;
import com.rhizodelta.core.service.GraphRootLocatorService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class RoutingRecallService {
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final GraphRootLocatorService graphRootLocatorService;
    private final ContextPruner contextPruner;

    public RoutingRecallService(
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            GraphRootLocatorService graphRootLocatorService,
            ContextPruner contextPruner
    ) {
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.graphRootLocatorService = graphRootLocatorService;
        this.contextPruner = contextPruner;
    }

    public PrunedContext recall(String content, String targetNodeId) {
        Objects.requireNonNull(content, "content must not be null");
        List<Float> vector = embeddingModelService.embed(content);
        List<SimilaritySearchResult> candidates = embeddingService.searchSimilar(
                vector,
                null,
                resolveRootId(targetNodeId)
        );
        return contextPruner.prune(candidates, targetNodeId);
    }

    private String resolveRootId(String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return null;
        }
        return graphRootLocatorService.resolveRootId(targetNodeId);
    }
}
