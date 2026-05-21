package com.rhizodelta.ai.context.api;

import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteRequest;
import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchRequest;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 暴露 embedding 写入与相似度检索接口。
 *
 * <p>该控制器位于 {@code com.rhizodelta.ai.context.api}，负责把节点向量写入能力和相似节点召回能力
 * 以 HTTP 形式暴露给上层。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>{@link #putEmbedding(String, EmbeddingWriteRequest)} 会写 Neo4j 节点的 {@code embedding} 字段。</li>
 *   <li>{@link #searchSimilar(SimilaritySearchRequest)} 只读访问向量索引，不修改图数据。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/nodes")
public class EmbeddingController {
    private final EmbeddingService embeddingService;
    private final EmbeddingModelService embeddingModelService;

    public EmbeddingController(EmbeddingService embeddingService,
                               EmbeddingModelService embeddingModelService) {
        this.embeddingService = embeddingService;
        this.embeddingModelService = embeddingModelService;
    }

    /**
     * 读取指定节点的 embedding 向量。
     *
     * @param id 节点 UUID 字符串。
     * @return 包含 vector 字段的响应，若节点无 embedding 则返回 404。
     */
    @GetMapping("/{id}/embedding")
    public ApiResponse<Map<String, Object>> getEmbedding(@PathVariable("id") String id) {
        List<Float> vector = embeddingService.getEmbedding(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("embedding not found for node: " + id));
        return ApiResponse.ok(Map.of("node_id", id, "vector", vector, "dimension", vector.size()));
    }

    /**
     * 为指定节点写入 embedding。
     *
     * <p>该接口通常由异步链路或上游 AI 能力调用，用于补全节点的向量表示。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @param request 向量写入请求。
     * @return 写入结果。
     */
    @PutMapping("/{id}/embedding")
    public ApiResponse<EmbeddingWriteResult> putEmbedding(
            @PathVariable("id") String id,
            @RequestBody EmbeddingWriteRequest request
    ) {
        EmbeddingWriteResult result = embeddingService.writeEmbedding(id, request.vector());
        return ApiResponse.ok(result);
    }

    /**
     * 按文本或向量检索相似节点。
     *
     * <p>这里使用 {@code POST} 而不是 {@code GET}，是因为查询向量通常过长，不适合放入 URL。
     * 调用方可以直接传 {@code vector}，也可以传 {@code query} 由服务端生成 embedding。
     * 若两者同时传入，优先使用 {@code vector}，以保持既有 API 行为稳定。
     *
     * <p>
     *
     * @param request 相似度检索请求。
     * @return 相似节点列表。
     */
    @PostMapping("/search/similar")
    public ApiResponse<List<SimilaritySearchResult>> searchSimilar(
            @RequestBody SimilaritySearchRequest request
    ) {
        List<Float> vector = request.vector();
        if (vector == null || vector.isEmpty()) {
            vector = embeddingModelService.embed(request.query());
        }
        List<SimilaritySearchResult> results = embeddingService.searchSimilar(vector, request.top_k());
        return ApiResponse.ok(results);
    }
}
