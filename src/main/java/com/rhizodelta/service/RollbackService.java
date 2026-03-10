package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import com.rhizodelta.domain.decision.RollbackResult;
import com.rhizodelta.exception.RollbackBlockedException;
import org.neo4j.driver.summary.ResultSummary;
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

    private static final String ATOMIC_DELETE_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})
            OPTIONAL MATCH (target)<-[:BRANCHED_FROM|MERGED_INTO]-(dep:GraphNode)
            WITH target, collect(DISTINCT dep.node_id) AS deps
            WHERE size(deps) = 0
            DETACH DELETE target
            """;

    private static final String FIND_DEPENDENTS_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})<-[:BRANCHED_FROM|MERGED_INTO]-(dependent:GraphNode)
            RETURN DISTINCT dependent.node_id AS dependentNodeId
            ORDER BY dependentNodeId
            """;

    private final Neo4jClient neo4jClient;

    public RollbackService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager")
    public RollbackResult rollbackDecision(String decisionId) {
        String validatedDecisionId = DecisionCommandValidation.requireText(decisionId, "decision_id");
        UUID decisionNodeId = resolveDecisionNodeId(validatedDecisionId);

        ResultSummary summary = neo4jClient.query(ATOMIC_DELETE_QUERY)
                .bind(decisionNodeId.toString())
                .to("nodeId")
                .run();

        if (summary.counters().nodesDeleted() == 0) {
            List<UUID> dependentNodeIds = findDependentNodeIds(decisionNodeId);
            throw new RollbackBlockedException(dependentNodeIds);
        }

        long relationshipsRemoved = summary.counters().relationshipsDeleted();
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
