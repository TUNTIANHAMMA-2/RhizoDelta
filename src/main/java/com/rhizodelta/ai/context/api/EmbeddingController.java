package com.rhizodelta.ai.context.api;

import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteRequest;
import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchRequest;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/nodes")
public class EmbeddingController {
    private final EmbeddingService embeddingService;

    public EmbeddingController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PutMapping("/{id}/embedding")
    public ApiResponse<EmbeddingWriteResult> putEmbedding(
            @PathVariable("id") String id,
            @RequestBody EmbeddingWriteRequest request
    ) {
        EmbeddingWriteResult result = embeddingService.writeEmbedding(id, request.vector());
        return ApiResponse.ok(result);
    }

    // POST is used instead of GET because the vector payload may exceed URL length limits.
    // This operation is idempotent and read-only.
    @PostMapping("/search/similar")
    public ApiResponse<List<SimilaritySearchResult>> searchSimilar(
            @RequestBody SimilaritySearchRequest request
    ) {
        List<SimilaritySearchResult> results = embeddingService.searchSimilar(request.vector(), request.top_k());
        return ApiResponse.ok(results);
    }
}
