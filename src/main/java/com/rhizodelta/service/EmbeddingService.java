package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.domain.embedding.NeighborInfo;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
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

    private static final String SIMILARITY_SEARCH_QUERY = """
            CALL db.index.vector.queryNodes('%s', $topK, $vector)
            YIELD node, score
            WITH node, score, labels(node) AS nodeLabels
            CALL {
              WITH node
              OPTIONAL MATCH (node)-[rel:MERGED_INTO|BRANCHED_FROM|SYNTHESIZED_FROM]-(neighbor:GraphNode)
              WHERE neighbor IS NOT NULL
              RETURN collect(DISTINCT {
                nodeId: neighbor.node_id,
                label: CASE WHEN 'Human_Post' IN labels(neighbor) THEN 'Human_Post' ELSE 'AI_Consensus' END,
                relationshipType: type(rel)
              }) AS neighbors
            }
            RETURN node.node_id AS nodeId,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN 'Human_Post' ELSE 'AI_Consensus' END AS label,
                   score AS score,
                   CASE WHEN 'Human_Post' IN nodeLabels THEN node.content ELSE node.summary_content END AS content,
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

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<SimilaritySearchResult> searchSimilar(List<Float> vector, Integer topK) {
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
                .toList();
    }

    private static NeighborInfo toNeighborInfo(Object entry) {
        if (!(entry instanceof Map<?, ?> row)) {
            throw new IllegalArgumentException("neighbor entry must be a map");
        }
        Object nodeId = row.get("nodeId");
        String label = (String) row.get("label");
        String relationshipType = (String) row.get("relationshipType");
        return new NeighborInfo(parseRequiredUuid(nodeId, "node_id"), label, relationshipType);
    }
}
