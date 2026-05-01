package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 负责把决策的元数据沉淀为 {@code (:Decision)} 节点，并与产出节点建立 RESULTED_IN 关系。
 *
 * <p>该节点是 {@code (:UserAccount)-[:REVIEWED]->(:Decision)} 审计关系的稳定锚点：
 * 没有它，{@link AuditRelationService} 的 REVIEWED 边会因找不到 Decision 节点而静默失败。
 *
 * <p>所有写入均为 idempotent MERGE，可在事务中重复触发。
 */
@Service
public class DecisionMetadataService {

    private static final String UPSERT_DECISION_QUERY = """
            MERGE (d:Decision {decision_id: $decisionId})
              ON CREATE SET d.decision_type = $decisionType,
                            d.operator_type = $operatorType,
                            d.operator_id = $operatorId,
                            d.reason = $reason,
                            d.created_at = $createdAt
            WITH d
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (d)-[r:RESULTED_IN]->(target)
              ON CREATE SET r.created_at = $createdAt
            RETURN d.decision_id AS decisionId
            """;

    private final Neo4jClient neo4jClient;

    public DecisionMetadataService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 写入或追加一条决策元数据。
     *
     * <p>幂等：相同 {@code decision_id} 多次调用只会保留首次的属性，并复用已建立的 RESULTED_IN 边。
     */
    public void recordDecision(
            String decisionId,
            String decisionType,
            DecisionOperatorType operatorType,
            String operatorId,
            UUID targetNodeId,
            String reason,
            OffsetDateTime createdAt
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("decisionId", decisionId);
        params.put("decisionType", decisionType);
        params.put("operatorType", operatorType.name());
        params.put("operatorId", operatorId);
        params.put("reason", reason == null ? "" : reason);
        params.put("targetNodeId", targetNodeId.toString());
        params.put("createdAt", createdAt);
        neo4jClient.query(UPSERT_DECISION_QUERY).bindAll(params).run();
    }
}
