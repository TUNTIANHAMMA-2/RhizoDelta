package com.rhizodelta.consensus.domain.node;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示沉淀到结果层的图节点。
 *
 * <p>该实体用于承载已经脱离普通帖子或共识摘要语义的“结果对象”，通常由物化或跨综合等决策产生，
 * 是结果层继续演化的基础。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>实体不可变，适合作为只读领域对象在查询和审计场景中传递。</li>
 *   <li>记录了决策来源和操作者信息，便于追踪结果是如何生成的。</li>
 *   <li>{@code embedding} 允许为空，因为可能在提交后异步生成。</li>
 * </ul>
 */
@Node({"Result", "GraphNode"})
public final class Result {
    @Id
    @Property("node_id")
    private final UUID nodeId;

    @Property("content")
    private final String content;

    @Property("operator_type")
    private final String operatorType;

    @Property("operator_id")
    private final String operatorId;

    @Property("request_id")
    private final String requestId;

    @Property("decision_id")
    private final String decisionId;

    @Property("created_at")
    private final Instant createdAt;

    @Nullable
    @Property("embedding")
    private final List<Float> embedding;

    @PersistenceCreator
    /**
     * 使用持久化字段重建结果实体。
     *
     * <p>该构造器主要由持久化映射层调用，用于确保恢复出的结果对象保持完整元数据。
     */
    public Result(
            UUID nodeId,
            String content,
            String operatorType,
            String operatorId,
            String requestId,
            String decisionId,
            Instant createdAt,
            @Nullable List<Float> embedding
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.operatorType = Objects.requireNonNull(operatorType, "operatorType must not be null");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.decisionId = Objects.requireNonNull(decisionId, "decisionId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getContent() {
        return content;
    }

    public String getOperatorType() {
        return operatorType;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public List<Float> getEmbedding() {
        return embedding == null ? null : List.copyOf(embedding);
    }
}
