package com.rhizodelta.service;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class RollbackService {
    private static final String FIND_DECISION_NODE_QUERY = """
            MATCH (decision:GraphNode {decision_id: $decisionId})
            WHERE 'AI_Consensus' IN labels(decision) OR 'Human_Post' IN labels(decision)
            RETURN decision.node_id AS nodeId
            LIMIT 1
            """;

    private static final String FIND_DEPENDENTS_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})<-[rel:BRANCHED_FROM|MERGED_INTO]-(dependent:GraphNode)
            RETURN DISTINCT dependent.node_id AS dependentNodeId
            ORDER BY dependentNodeId
            """;

    private static final String DELETE_DECISION_QUERY = """
            MATCH (decision:GraphNode {node_id: $nodeId})
            OPTIONAL MATCH (decision)-[outgoing]-()
            WITH decision, count(outgoing) AS outgoingCount
            OPTIONAL MATCH ()-[incoming]->(decision)
            WITH decision, outgoingCount, count(incoming) AS incomingCount
            DETACH DELETE decision
            RETURN outgoingCount + incomingCount AS relationshipsRemoved
            """;

    private final Neo4jClient neo4jClient;

    public RollbackService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager")
    public RollbackResult rollbackDecision(String decisionId) {
        String validatedDecisionId = DecisionCommandValidation.requireText(decisionId, "decision_id");
        UUID decisionNodeId = resolveDecisionNodeId(validatedDecisionId);
        List<UUID> dependentNodeIds = findDependentNodeIds(decisionNodeId);
        if (!dependentNodeIds.isEmpty()) {
            throw new RollbackBlockedException(dependentNodeIds);
        }
        long relationshipsRemoved = deleteDecisionNode(decisionNodeId);
        return new RollbackResult(validatedDecisionId, decisionNodeId, relationshipsRemoved);
    }

    private UUID resolveDecisionNodeId(String decisionId) {
        Map<String, Object> row = neo4jClient.query(FIND_DECISION_NODE_QUERY)
                .bind(decisionId)
                .to("decisionId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("decision not found: " + decisionId));
        return toUuid(row.get("nodeId"), "nodeId");
    }

    private List<UUID> findDependentNodeIds(UUID nodeId) {
        Collection<Map<String, Object>> rows = neo4jClient.query(FIND_DEPENDENTS_QUERY)
                .bind(nodeId.toString())
                .to("nodeId")
                .fetch()
                .all();
        return rows.stream()
                .map(row -> toUuid(row.get("dependentNodeId"), "dependentNodeId"))
                .toList();
    }

    private long deleteDecisionNode(UUID nodeId) {
        Map<String, Object> row = neo4jClient.query(DELETE_DECISION_QUERY)
                .bind(nodeId.toString())
                .to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("failed to delete decision node: " + nodeId));
        Object value = row.get("relationshipsRemoved");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("relationshipsRemoved must be numeric");
        }
        return number.longValue();
    }

    private static UUID toUuid(Object value, String fieldName) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            return UUID.fromString(text);
        }
        throw new IllegalArgumentException(fieldName + " must be uuid");
    }
}
