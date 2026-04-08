package com.rhizodelta.ai.routing.service;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * 在真正提交路由决策前检查图状态是否已发生变化。
 *
 * <p>该守卫用于防止“模型基于旧上下文做出的判断”在图已经前进后仍然落地，
 * 从而降低并发场景下错误合并或错误分支的风险。
 */
@Service
public class PreCommitGuard {
    private static final String SOURCE_STALENESS_QUERY = """
            OPTIONAL MATCH (source:GraphNode {node_id: $sourceNodeId})
            WHERE NOT coalesce(source._deleted, false)
            OPTIONAL MATCH (source)<-[rel:BRANCHED_FROM|MERGED_INTO|CONTINUES_FROM|CONVERGED_FROM]-(child:GraphNode)
            WHERE NOT coalesce(child._deleted, false)
              AND child.created_at > $workflowStartedAt
            RETURN source IS NOT NULL AS sourcePresent,
                   count(child) > 0 AS sourceAdvanced
            """;

    private static final String TARGET_STALENESS_QUERY = """
            MATCH (target:GraphNode {node_id: $targetNodeId})
            WHERE NOT coalesce(target._deleted, false)
            OPTIONAL MATCH (head:GraphNode)-[rel:MERGED_INTO|CONVERGED_FROM]->(target)
            WHERE NOT coalesce(head._deleted, false)
              AND head.created_at > $workflowStartedAt
            RETURN count(head) > 0 AS targetAdvanced
            """;

    private final Neo4jClient neo4jClient;

    public PreCommitGuard(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 判断源节点或目标节点在工作流开始后是否已经前进。
     *
     * <p>若源分支已前进、目标头节点已前进，或者源节点本身消失，都会返回
     * {@code stale=true}，提示上层转入人工复核。
     *
     * <p>
     *
     * @param sourceNodeId 源节点 ID。
     * @param workflowStartedAt 工作流开始时间。
     * @param targetNodeId 可选目标节点 ID。
     * @return 提交流程守卫结果。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public PreCommitGuardResult evaluate(String sourceNodeId, Instant workflowStartedAt, String targetNodeId) {
        Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
        Objects.requireNonNull(workflowStartedAt, "workflowStartedAt must not be null");
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(workflowStartedAt, ZoneOffset.UTC);

        Map<String, Object> sourceResult = neo4jClient.query(SOURCE_STALENESS_QUERY)
                .bind(sourceNodeId).to("sourceNodeId")
                .bind(startedAt).to("workflowStartedAt")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("failed to evaluate source staleness"));
        if (!Boolean.TRUE.equals(sourceResult.get("sourcePresent"))) {
            return new PreCommitGuardResult(true, "source node missing");
        }
        if (Boolean.TRUE.equals(sourceResult.get("sourceAdvanced"))) {
            return new PreCommitGuardResult(true, "source branch advanced during workflow");
        }
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return new PreCommitGuardResult(false, "graph unchanged");
        }
        Map<String, Object> targetResult = neo4jClient.query(TARGET_STALENESS_QUERY)
                .bind(targetNodeId).to("targetNodeId")
                .bind(startedAt).to("workflowStartedAt")
                .fetch()
                .one()
                .orElse(Map.of("targetAdvanced", false));
        if (Boolean.TRUE.equals(targetResult.get("targetAdvanced"))) {
            return new PreCommitGuardResult(true, "target head advanced during workflow");
        }
        return new PreCommitGuardResult(false, "graph unchanged");
    }

    /**
     * 表示提交前守卫的判断结果。
     */
    public record PreCommitGuardResult(boolean stale, String reason) {
    }
}
