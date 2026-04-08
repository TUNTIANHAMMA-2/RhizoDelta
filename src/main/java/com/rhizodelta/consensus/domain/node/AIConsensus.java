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
 * 表示由多个来源节点汇聚出的 AI 共识节点。
 *
 * <p>该实体承载的是“阶段性共识摘要”，而不是原始帖子本身；后续查询、摘要增量更新和 AI 路由
 * 都会把它视为一类可继续演化的图节点。
 *
 * <p><b>设计特性</b>：
 * <ul>
 *   <li>实体保持<b>不可变</b>，对外返回的向量字段总是拷贝。</li>
 *   <li>{@code summaryContent} 是共识节点最核心的业务内容。</li>
 *   <li>{@code embedding} 允许为空，因为向量可能在事务提交后异步补写。</li>
 * </ul>
 */
@Node({"AI_Consensus", "GraphNode"})
public final class AIConsensus {
    @Id
    @Property("node_id")
    private final UUID nodeId;

    @Property("summary_content")
    private final String summaryContent;

    @Property("agent_version")
    private final String agentVersion;

    @Property("created_at")
    private final Instant createdAt;

    @Nullable
    @Property("embedding")
    private final List<Float> embedding;

    @PersistenceCreator
    /**
     * 使用持久化字段重建共识实体。
     *
     * <p>该构造器主要服务于 Neo4j 映射层，保证从数据库恢复出的实体仍然满足不可变约束。
     */
    public AIConsensus(
            UUID nodeId,
            String summaryContent,
            String agentVersion,
            Instant createdAt,
            @Nullable List<Float> embedding
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.summaryContent = Objects.requireNonNull(summaryContent, "summaryContent must not be null");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
    }

    /**
     * 创建一条尚未生成 embedding 的新共识节点。
     *
     * <p>该工厂方法适合在服务层只关心核心业务字段时快速构造共识实体。
     */
    public static AIConsensus create(UUID nodeId, String summaryContent, String agentVersion) {
        return new AIConsensus(nodeId, summaryContent, agentVersion, Instant.now(), null);
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getSummaryContent() {
        return summaryContent;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public List<Float> getEmbedding() {
        return embedding == null ? null : List.copyOf(embedding);
    }
}
