package com.rhizodelta.core.domain.node;

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
 * 表示一条由用户提交的原始帖子节点。
 *
 * <p>该实体是核心图谱中的基础输入单元，后续的查询、摘要、质量评估、AI 路由与共识决策
 * 都会围绕它继续演化。
 *
 * <p><b>设计特性</b>：
 * <ul>
 *   <li>实体本身是<b>不可变</b>的，对外暴露的集合字段会返回拷贝。</li>
 *   <li>同时具备 {@code Human_Post} 与 {@code GraphNode} 标签，便于查询层统一处理。</li>
 *   <li>{@code embedding} 允许为空，因为向量通常在异步链路中补写。</li>
 * </ul>
 */
@Node({"Human_Post", "GraphNode"})
public final class HumanPost {
    @Id
    @Property("node_id")
    private final UUID nodeId;

    @Property("content")
    private final String content;

    @Property("author_id")
    private final String authorId;

    @Property("request_id")
    private final String requestId;

    @Property("created_at")
    private final Instant createdAt;

    @Nullable
    @Property("embedding")
    private final List<Float> embedding;

    @PersistenceCreator
    /**
     * 使用持久化字段重建帖子实体。
     *
     * <p>该构造器主要服务于 Neo4j 映射层与测试场景，确保从持久化记录恢复出的对象仍保持不可变语义。
     */
    public HumanPost(
            UUID nodeId,
            String content,
            String authorId,
            String requestId,
            Instant createdAt,
            @Nullable List<Float> embedding
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
    }

    /**
     * 创建一条尚未生成 embedding 的新帖子实体。
     *
     * <p>该工厂方法存在，是为了让调用方在“只关心业务字段、不关心底层时间与向量初始化”的场景中，
     * 用更简洁的方式构造一条新帖子。
     */
    public static HumanPost create(UUID nodeId, String content, String authorId, String requestId) {
        return new HumanPost(nodeId, content, authorId, requestId, Instant.now(), null);
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getContent() {
        return content;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public List<Float> getEmbedding() {
        return embedding == null ? null : List.copyOf(embedding);
    }
}
