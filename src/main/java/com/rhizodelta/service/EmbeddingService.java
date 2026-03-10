package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class EmbeddingService {
    private static final String UPDATE_EMBEDDING_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            SET node.embedding = $embedding
            RETURN node.node_id AS nodeId
            """;

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
        return List.copyOf(vector);
    }
}
