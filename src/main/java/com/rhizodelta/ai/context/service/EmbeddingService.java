package com.rhizodelta.ai.context.service;

import com.rhizodelta.core.validation.DecisionCommandValidation;
import com.rhizodelta.ai.context.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.ai.context.domain.embedding.NeighborInfo;
import com.rhizodelta.ai.context.domain.embedding.SimilaritySearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 提供节点 embedding 写入与向量相似度检索能力。
 *
 * <p>该服务是 AI 上下文层的基础设施封装，负责把向量写回图节点，并通过 Neo4j 向量索引召回相似节点。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>{@link #writeEmbedding(String, List)} 会写 Neo4j 节点的 {@code embedding} 字段。</li>
 *   <li>{@link #searchSimilar(List, Integer, String)} 只读访问向量索引，不修改图数据。</li>
 *   <li>向量维度不匹配会直接抛异常，避免错误向量污染索引。</li>
 * </ul>
 */
@Service
public class EmbeddingService {
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final String VECTOR_INDEX_NAME = "rhizodelta_graph_node_embedding_idx";

    private static final String UPDATE_EMBEDDING_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            SET node.embedding = $embedding
            RETURN node.node_id AS nodeId
            """;

    private static final String GET_EMBEDDING_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE node.embedding IS NOT NULL
            RETURN node.embedding AS embedding
            """;

    private static final String SIMILARITY_SEARCH_QUERY = """
            CALL db.index.vector.queryNodes('%s', $topK, $vector)
            YIELD node, score
            WHERE NOT coalesce(node._deleted, false)
              AND node.node_id IS NOT NULL
              AND ($rootId IS NULL OR coalesce(node.root_id, node.node_id) = $rootId)
            WITH node, score, labels(node) AS nodeLabels
            CALL {
              WITH node
              OPTIONAL MATCH (node)-[rel:MERGED_INTO|BRANCHED_FROM|SYNTHESIZED_FROM]-(neighbor:GraphNode)
              WHERE neighbor IS NOT NULL
                AND neighbor.node_id IS NOT NULL
                AND NOT coalesce(neighbor._deleted, false)
              RETURN collect(DISTINCT {
                nodeId: neighbor.node_id,
                label: CASE WHEN 'Human_Post' IN labels(neighbor) THEN 'Human_Post' ELSE 'AI_Consensus' END,
                relationshipType: type(rel)
              }) AS neighbors
            }
            RETURN node.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' WHEN 'Result' IN nodeLabels THEN 'Result' ELSE 'AI_Consensus' END AS label,
                   score AS score,
                   CASE WHEN 'AI_Consensus' IN nodeLabels THEN node.summary_content ELSE node.content END AS content,
                   node.created_at AS createdAt,
                   neighbors AS neighbors
            ORDER BY score DESC
            """.formatted(VECTOR_INDEX_NAME);

    private final Neo4jClient neo4jClient;
    private final int embeddingDimension;

    public EmbeddingService(
            Neo4jClient neo4jClient,
            @Value("${rhizodelta.embedding.dimension}") int embeddingDimension
    ) {
        this.neo4jClient = neo4jClient;
        this.embeddingDimension = embeddingDimension;
    }

    /**
     * 将 embedding 写入指定节点。
     *
     * <p>该方法存在的意义，是把向量维度校验、节点存在性校验和写库动作统一收敛到一个事务入口。
     *
     * <p>
     *
     * @param nodeId 节点 ID。
     * @param vector embedding 向量。
     * @return 写入结果。
     */
    @Transactional(transactionManager = "transactionManager")
    public EmbeddingWriteResult writeEmbedding(String nodeId, List<Float> vector) {
        String validatedNodeId = DecisionCommandValidation.requireText(nodeId, "node_id");
        UUID parsedNodeId = parseNodeId(validatedNodeId);
        List<Float> validatedVector = requireVector(vector);
        int actualDimension = validatedVector.size();
        if (actualDimension != embeddingDimension) {
            throw new IllegalArgumentException(String.format(
                    "embedding dimension mismatch: expected %d, actual %d",
                    embeddingDimension,
                    actualDimension
            ));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("nodeId", validatedNodeId);
        params.put("embedding", validatedVector);
        neo4jClient.query(UPDATE_EMBEDDING_QUERY)
                .bindAll(params)
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("node not found: " + validatedNodeId));
        return new EmbeddingWriteResult(parsedNodeId, actualDimension);
    }

    /**
     * 读取指定节点的 embedding 向量。
     *
     * @param nodeId 节点 ID。
     * @return embedding 向量，若节点无 embedding 则返回空 Optional。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    @SuppressWarnings("unchecked")
    public java.util.Optional<List<Float>> getEmbedding(String nodeId) {
        String validatedNodeId = DecisionCommandValidation.requireText(nodeId, "node_id");
        parseNodeId(validatedNodeId); // validate UUID format

        return neo4jClient.query(GET_EMBEDDING_QUERY)
                .bind(validatedNodeId).to("nodeId")
                .fetch()
                .one()
                .map(row -> {
                    Object raw = row.get("embedding");
                    if (raw instanceof List<?> list) {
                        return list.stream()
                                .map(v -> v instanceof Number n ? n.floatValue() : Float.parseFloat(v.toString()))
                                .toList();
                    }
                    return List.<Float>of();
                });
    }

    /**
     * 按默认召回数量搜索相似节点。
     *
     * <p>这是 {@link #searchSimilar(List, Integer, String)} 的便捷重载，
     * 不限制根节点范围。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<SimilaritySearchResult> searchSimilar(List<Float> vector, Integer topK) {
        return searchSimilar(vector, topK, null);
    }

    /**
     * 搜索与给定向量最相似的节点。
     *
     * <p>该方法可选按 {@code rootId} 限制检索范围，从而把召回限制在同一谱系内。
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>只会返回未删除节点。</li>
     *   <li>结果中会附带邻居节点摘要，便于后续上下文构建与路由判断。</li>
     * </ul>
     *
     * <p>
     *
     * @param vector 查询向量。
     * @param topK 可选召回数量。
     * @param rootId 可选根节点限制。
     * @return 相似节点列表。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<SimilaritySearchResult> searchSimilar(List<Float> vector, Integer topK, String rootId) {
        List<Float> validatedVector = requireVector(vector);
        int actualDimension = validatedVector.size();
        if (actualDimension != embeddingDimension) {
            throw new IllegalArgumentException(String.format(
                    "embedding dimension mismatch: expected %d, actual %d",
                    embeddingDimension,
                    actualDimension
            ));
        }
        int resolvedTopK = resolveTopK(topK);
        Map<String, Object> params = new HashMap<>();
        params.put("vector", validatedVector);
        params.put("topK", resolvedTopK);
        params.put("rootId", normalizeRootId(rootId));

        Collection<Map<String, Object>> records = neo4jClient.query(SIMILARITY_SEARCH_QUERY)
                .bindAll(params)
                .fetch()
                .all();
        return records.stream()
                .map(EmbeddingService::toSimilaritySearchResult)
                .toList();
    }

    private static UUID parseNodeId(String nodeId) {
        try {
            return UUID.fromString(nodeId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("node_id must be a valid UUID", exception);
        }
    }

    private static List<Float> requireVector(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        for (int i = 0; i < vector.size(); i++) {
            Float element = vector.get(i);
            if (element == null) {
                throw new IllegalArgumentException("vector must not contain null elements");
            }
            if (Float.isNaN(element) || Float.isInfinite(element)) {
                throw new IllegalArgumentException("vector must not contain NaN or infinite values");
            }
        }
        return List.copyOf(vector);
    }

    private static int resolveTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("top_k must be greater than 0");
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static String normalizeRootId(String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return null;
        }
        return rootId;
    }

    private static SimilaritySearchResult toSimilaritySearchResult(Map<String, Object> row) {
        UUID nodeId = parseRequiredUuid(row.get("nodeId"), "node_id");
        String label = (String) row.get("label");
        Double score = toScore(row.get("score"));
        String content = (String) row.get("content");
        Instant createdAt = toInstant(row.get("createdAt"));
        List<NeighborInfo> neighbors = toNeighbors(row.get("neighbors"));
        return new SimilaritySearchResult(nodeId, label, score, content, createdAt, neighbors);
    }

    private static UUID parseRequiredUuid(Object value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        return UUID.fromString(value.toString());
    }

    private static Double toScore(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalStateException("score must be numeric");
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        return null;
    }

    private static List<NeighborInfo> toNeighbors(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException("neighbors must be a list");
        }
        return entries.stream()
                .filter(Objects::nonNull)
                .map(EmbeddingService::toNeighborInfo)
                .filter(Objects::nonNull)
                .toList();
    }

    private static NeighborInfo toNeighborInfo(Object entry) {
        if (!(entry instanceof Map<?, ?> row)) {
            throw new IllegalArgumentException("neighbor entry must be a map");
        }
        Object nodeId = row.get("nodeId");
        if (nodeId == null) {
            return null;
        }
        String label = (String) row.get("label");
        String relationshipType = (String) row.get("relationshipType");
        return new NeighborInfo(parseRequiredUuid(nodeId, "node_id"), label, relationshipType);
    }
}
