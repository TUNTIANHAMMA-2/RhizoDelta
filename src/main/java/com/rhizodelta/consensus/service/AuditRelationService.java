package com.rhizodelta.consensus.service;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditRelationService {
    private static final String CREATE_REVIEWED_QUERY = """
            MATCH (u:UserAccount {user_id: $reviewerId})
            MATCH (d:Decision {decision_id: $decisionId})
            MERGE (u)-[r:REVIEWED]->(d)
              ON CREATE SET r.decision_id = $decisionId,
                            r.outcome = $outcome,
                            r.at = datetime()
              ON MATCH SET r.outcome = $outcome,
                            r.at = datetime()
            RETURN r.outcome AS outcome
            """;
    private static final String CREATE_OPERATED_QUERY = """
            MATCH (u:UserAccount {user_id: $operatorId})
            MATCH (n:GraphNode {node_id: $nodeId})
            MERGE (u)-[r:OPERATED]->(n)
              ON CREATE SET r.operation_id = $operationId, r.at = datetime()
              ON MATCH SET r.operation_id = $operationId, r.at = datetime()
            RETURN r.operation_id AS operation_id
            """;
    private static final String REVIEW_HISTORY_QUERY = """
            MATCH (u:UserAccount)-[r:REVIEWED]->(d:Decision {decision_id: $decisionId})
            OPTIONAL MATCH (u)-[:HAS_PROFILE]->(p:UserProfile)
            RETURN u.user_id AS reviewer_id,
                   u.username AS reviewer_username,
                   coalesce(p.display_name, u.username) AS reviewer_display_name,
                   r.outcome AS outcome,
                   toString(r.at) AS reviewed_at
            ORDER BY r.at DESC
            """;
    private static final String OPERATION_HISTORY_QUERY = """
            MATCH (u:UserAccount)-[r:OPERATED]->(n:GraphNode {node_id: $nodeId})
            OPTIONAL MATCH (u)-[:HAS_PROFILE]->(p:UserProfile)
            RETURN u.user_id AS operator_id,
                   u.username AS operator_username,
                   coalesce(p.display_name, u.username) AS operator_display_name,
                   r.operation_id AS operation_id,
                   toString(r.at) AS operated_at
            ORDER BY r.at DESC
            """;

    private final Neo4jClient neo4jClient;

    public AuditRelationService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public void recordReview(String reviewerId, String decisionId, String outcome) {
        neo4jClient.query(CREATE_REVIEWED_QUERY)
                .bindAll(Map.of("reviewerId", reviewerId, "decisionId", decisionId, "outcome", outcome))
                .run();
    }

    public void recordOperation(String operatorId, String nodeId, String operationId) {
        neo4jClient.query(CREATE_OPERATED_QUERY)
                .bindAll(Map.of("operatorId", operatorId, "nodeId", nodeId, "operationId", operationId))
                .run();
    }

    /**
     * 查询某条决策的所有人工复核历史。
     */
    public List<Map<String, Object>> getReviewHistory(String decisionId) {
        return neo4jClient.query(REVIEW_HISTORY_QUERY)
                .bind(decisionId).to("decisionId")
                .fetch()
                .all()
                .stream().toList();
    }

    /**
     * 查询某个节点的所有管理操作历史。
     */
    public List<Map<String, Object>> getOperationHistory(String nodeId) {
        return neo4jClient.query(OPERATION_HISTORY_QUERY)
                .bind(nodeId).to("nodeId")
                .fetch()
                .all()
                .stream().toList();
    }
}
