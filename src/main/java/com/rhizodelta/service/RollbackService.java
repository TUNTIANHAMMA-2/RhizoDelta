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
    private static final String LABEL_AI_CONSENSUS = "AI_Consensus";
    private static final String LABEL_HUMAN_POST = "Human_Post";
    private static final String FIND_DECISION_NODE_QUERY = """
            MATCH (decision:GraphNode {decision_id: $decisionId})
            WHERE 'AI_Consensus' IN labels(decision) OR 'Human_Post' IN labels(decision)
            RETURN decision.node_id AS nodeId,
                   labels(decision) AS labels
            LIMIT 1
            """;

    private static final int MIN_PROPERTIES_UPDATED = 1;
    private static final int MIN_NODES_DELETED = 1;
    private static final long NO_RELATIONSHIPS_REMOVED = 0L;
    private static final String FIND_SOFT_DELETE_STATUS_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})
            RETURN coalesce(target._deleted, false) AS deleted
            """;
    private static final String ATOMIC_BRANCH_ROLLBACK_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})
            OPTIONAL MATCH (target)<-[:BRANCHED_FROM|MERGED_INTO]-(dep:GraphNode)
            WHERE NOT coalesce(dep._deleted, false)
            WITH target, collect(DISTINCT dep.node_id) AS deps
            WHERE size(deps) = 0
            SET target._deleted = true,
                target._deleted_at = datetime()
            WITH target
            OPTIONAL MATCH (target)-[rel]-()
            DELETE rel
            """;
    private static final String ATOMIC_MERGE_ROLLBACK_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})
            OPTIONAL MATCH (target)<-[:BRANCHED_FROM|MERGED_INTO]-(dep:GraphNode)
            WHERE NOT coalesce(dep._deleted, false)
            WITH target, collect(DISTINCT dep.node_id) AS deps
            WHERE size(deps) = 0
            DETACH DELETE target
            """;

    private static final String FIND_DEPENDENTS_QUERY = """
            MATCH (target:GraphNode {node_id: $nodeId})<-[:BRANCHED_FROM|MERGED_INTO]-(dependent:GraphNode)
            WHERE NOT coalesce(dependent._deleted, false)
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
        DecisionNode decisionNode = resolveDecisionNode(validatedDecisionId);

        if (decisionNode.type() == DecisionNodeType.MERGE) {
            return rollbackMerge(validatedDecisionId, decisionNode.nodeId());
        }

        return rollbackBranch(validatedDecisionId, decisionNode.nodeId());
    }

    private RollbackResult rollbackBranch(String decisionId, UUID nodeId) {
        if (isSoftDeleted(nodeId)) {
            return new RollbackResult(decisionId, nodeId, NO_RELATIONSHIPS_REMOVED, true);
        }
        ResultSummary summary = runRollbackQuery(ATOMIC_BRANCH_ROLLBACK_QUERY, nodeId);
        if (summary.counters().propertiesSet() < MIN_PROPERTIES_UPDATED) {
            throw new RollbackBlockedException(findDependentNodeIds(nodeId));
        }
        return new RollbackResult(decisionId, nodeId, summary.counters().relationshipsDeleted(), true);
    }

    private RollbackResult rollbackMerge(String decisionId, UUID nodeId) {
        ResultSummary summary = runRollbackQuery(ATOMIC_MERGE_ROLLBACK_QUERY, nodeId);
        if (summary.counters().nodesDeleted() < MIN_NODES_DELETED) {
            List<UUID> dependents = findDependentNodeIds(nodeId);
            if (dependents.isEmpty()) {
                return new RollbackResult(decisionId, nodeId, NO_RELATIONSHIPS_REMOVED, true);
            }
            throw new RollbackBlockedException(dependents);
        }
        return new RollbackResult(decisionId, nodeId, summary.counters().relationshipsDeleted(), true);
    }

    private boolean isSoftDeleted(UUID nodeId) {
        Map<String, Object> row = neo4jClient.query(FIND_SOFT_DELETE_STATUS_QUERY)
                .bind(nodeId.toString())
                .to("nodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("node not found: " + nodeId));
        return Boolean.TRUE.equals(row.get("deleted"));
    }

    private DecisionNode resolveDecisionNode(String decisionId) {
        Map<String, Object> row = neo4jClient.query(FIND_DECISION_NODE_QUERY)
                .bind(decisionId)
                .to("decisionId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("decision not found: " + decisionId));
        UUID nodeId = toUuid(row.get("nodeId"), "nodeId");
        List<String> labels = toStringList(row.get("labels"), "labels");
        return new DecisionNode(nodeId, toDecisionNodeType(labels));
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

    private ResultSummary runRollbackQuery(String query, UUID nodeId) {
        return neo4jClient.query(query)
                .bind(nodeId.toString())
                .to("nodeId")
                .run();
    }

    private static DecisionNodeType toDecisionNodeType(List<String> labels) {
        if (labels.contains(LABEL_AI_CONSENSUS)) {
            return DecisionNodeType.MERGE;
        }
        if (labels.contains(LABEL_HUMAN_POST)) {
            return DecisionNodeType.BRANCH;
        }
        throw new IllegalArgumentException("decision node must be AI_Consensus or Human_Post");
    }

    private static List<String> toStringList(Object value, String fieldName) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new IllegalArgumentException(fieldName + " must be list");
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

    private record DecisionNode(UUID nodeId, DecisionNodeType type) {
    }

    private enum DecisionNodeType {
        MERGE,
        BRANCH
    }
}
