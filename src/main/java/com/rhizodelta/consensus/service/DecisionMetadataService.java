package com.rhizodelta.consensus.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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

    /**
     * 先 MATCH 目标节点再 MERGE Decision —— 这样若 target 不存在，
     * 整条语句返回 0 行，不会留下没有 RESULTED_IN 边的孤立 Decision 节点。
     */
    private static final String UPSERT_DECISION_QUERY = """
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (d:Decision {decision_id: $decisionId})
              ON CREATE SET d.decision_type = $decisionType,
                            d.operator_type = $operatorType,
                            d.operator_id = $operatorId,
                            d.reason = $reason,
                            d.created_at = $createdAt
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
     *
     * @throws NoSuchElementException 当 {@code targetNodeId} 不存在时；调用方应保证产出节点已先行写入。
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
        Optional<Map<String, Object>> row = neo4jClient.query(UPSERT_DECISION_QUERY)
                .bindAll(params)
                .fetch()
                .one();
        if (row.isEmpty()) {
            throw new NoSuchElementException(
                    "target node not found while recording decision metadata: " + targetNodeId);
        }
    }
}
