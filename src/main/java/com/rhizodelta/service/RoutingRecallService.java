package com.rhizodelta.service;

import com.rhizodelta.domain.embedding.PrunedContext;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class RoutingRecallService {
    private final EmbeddingModelService embeddingModelService;
    private final EmbeddingService embeddingService;
    private final ContextPruner contextPruner;

    public RoutingRecallService(
            EmbeddingModelService embeddingModelService,
            EmbeddingService embeddingService,
            ContextPruner contextPruner
    ) {
        this.embeddingModelService = embeddingModelService;
        this.embeddingService = embeddingService;
        this.contextPruner = contextPruner;
    }

    public PrunedContext recall(String content, String targetNodeId) {
        Objects.requireNonNull(content, "content must not be null");
        List<Float> vector = embeddingModelService.embed(content);
        List<SimilaritySearchResult> candidates = embeddingService.searchSimilar(vector, null);
        return contextPruner.prune(candidates, targetNodeId);
    }
}
