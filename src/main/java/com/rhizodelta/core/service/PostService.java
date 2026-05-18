package com.rhizodelta.core.service;

import com.rhizodelta.core.domain.node.HumanPost;
import com.rhizodelta.core.repository.HumanPostRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists human-authored posts into the graph with transactional dual-write
 * semantics: {@code author_id} projection + canonical {@code AUTHORED} edge
 * are written in the same transaction.
 */
@Service
public class PostService {
    private static final String HUMAN_OPERATOR_TYPE = "HUMAN";
    private static final String USER_REPLY_REASON = "user reply";
    private static final String UPSERT_HUMAN_POST_QUERY = """
            OPTIONAL MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (post:Human_Post {request_id: $requestId})
            ON CREATE SET
              post:GraphNode,
              post.node_id = $nodeId,
              post.content = $content,
              post.author_id = $authorId,
              post.request_id = $requestId,
              post.target_node_id = $targetNodeId,
              post.root_id = CASE
                WHEN target IS NULL THEN $nodeId
                ELSE coalesce(target.root_id, target.node_id)
              END,
              post.created_at = $createdAt,
              post.embedding = null
            RETURN toString(post.node_id) AS nodeId
            """;

    private static final String FIND_NODE_ID_BY_REQUEST_ID_QUERY = """
            MATCH (post:Human_Post {request_id: $requestId})
            RETURN toString(post.node_id) AS nodeId
            """;
    private static final String AUTHOR_EXISTS_QUERY = """
            MATCH (user:UserAccount {user_id: $authorId})
            RETURN count(user) > 0 AS exists
            """;

    private static final String TARGET_NODE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $targetNodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0 AS exists
            """;
    private static final String CREATE_AUTHORED_RELATIONSHIP_QUERY = """
            MATCH (post:Human_Post:GraphNode {node_id: $postNodeId})
            MATCH (author:UserAccount {user_id: $authorId})
            MERGE (author)-[rel:AUTHORED]->(post)
              ON CREATE SET rel.created_at  = $createdAt,
                            rel.authored_id = $authorId + ':' + $postNodeId
            RETURN type(rel) AS relType
            """;
    /**
     * 幂等命中（{@code request_id} 已存在）时用的补偿查询：
     * 如果由于历史数据 / 回滚 / 早期版本写入只保留了 {@code author_id} 投影、
     * 没有写过 canonical AUTHORED 边，这里会把它补上；反之命中既有边后什么都不做。
     */
    private static final String COMPENSATE_AUTHORED_RELATIONSHIP_QUERY = """
            MATCH (post:Human_Post:GraphNode {node_id: $postNodeId})
            WHERE post.author_id IS NOT NULL
            MATCH (author:UserAccount {user_id: post.author_id})
            MERGE (author)-[rel:AUTHORED]->(post)
              ON CREATE SET rel.created_at  = coalesce(post.created_at, datetime()),
                            rel.authored_id = author.user_id + ':' + post.node_id
            RETURN type(rel) AS relType
            """;
    private static final String CREATE_REPLY_RELATIONSHIP_QUERY = """
            MATCH (post:Human_Post:GraphNode {node_id: $postNodeId})
            MATCH (target:GraphNode {node_id: $targetNodeId})
            MERGE (post)-[rel:CONTINUES_FROM]->(target)
            ON CREATE SET
              rel.operator_type = $operatorType,
              rel.operator_id = $operatorId,
              rel.created_at = $createdAt,
              rel.reason = $reason
            RETURN type(rel) AS relType
            """;

    private final Neo4jClient neo4jClient;
    private final HumanPostRepository humanPostRepository;

    public PostService(Neo4jClient neo4jClient, HumanPostRepository humanPostRepository) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
    }

    /**
     * 创建一条人工帖子节点，并在需要时补上回复关系。
     *
     * <p>该方法存在的意义，是把“帖子节点创建”“目标节点校验”“回复边创建”收敛为单个事务边界，
     * 保证调用方拿到的结果要么是已存在的幂等节点，要么是一次完整写入后的新节点。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会写 Neo4j 节点。</li>
     *   <li>在 {@code targetNodeId} 存在时会写 {@code CONTINUES_FROM} 关系。</li>
     *   <li>会读取仓储确认最终持久化结果，而不是直接信任 upsert 查询结果。</li>
     * </ul>
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>当 {@code requestId} 已存在时，返回结果中的 {@code created=false}。</li>
     *   <li>该方法是下游 embedding、质量评估与 AI 路由链路的前置基础，不应在失败时静默降级。</li>
     * </ul>
     *
     * <p>
     *
     * @param command 帖子创建命令。
     * @return 包含最终帖子节点及是否新建的结果对象。
     */
    @Transactional(transactionManager = "transactionManager")
    public CreateHumanPostResult createHumanPost(CreateHumanPostCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        String existingNodeId = findNodeIdByRequestId(command.requestId());
        if (existingNodeId != null) {
            // 幂等命中分支也要校验 / 补建 canonical AUTHORED 边：历史数据、回滚回放、
            // 或早期版本（只写 author_id 不写 AUTHORED）落库的帖子在 retry 时不能继续漏边。
            compensateAuthoredRelationship(existingNodeId);
            HumanPost existing = humanPostRepository.findByNodeId(UUID.fromString(existingNodeId))
                    .orElseThrow(() -> new IllegalStateException("Human_Post not found after upsert"));
            return new CreateHumanPostResult(existing, false);
        }

        validateAuthorExists(command.authorId());
        if (command.targetNodeId() != null) {
            validateTargetNodeExists(command.targetNodeId());
        }

        UUID generatedNodeId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        String nodeIdString = upsertByRequestId(command, generatedNodeId, createdAt);
        createAuthoredRelationship(nodeIdString, command.authorId(), createdAt);
        createReplyRelationshipIfNeeded(nodeIdString, command, createdAt);
        UUID persistedNodeId = UUID.fromString(nodeIdString);

        HumanPost created = humanPostRepository.findByNodeId(persistedNodeId)
                .orElseThrow(() -> new IllegalStateException("Human_Post not found after upsert"));
        return new CreateHumanPostResult(created, true);
    }

    /**
     * 表示帖子创建结果。
     *
     * <p>该结果对象用于让调用方区分“新创建成功”与“命中了幂等复用”两种语义。
     */
    public record CreateHumanPostResult(HumanPost post, boolean created) {
    }

    /**
     * 按 {@code requestId} 执行帖子节点 upsert。
     *
     * <p>该方法单独存在，是为了把节点属性初始化和根节点归属计算封装在一个 Neo4j 语句里，
     * 避免调用方在事务中重复拼接图谱建模细节。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会写或复用 {@code Human_Post} 节点。</li>
     *   <li>会根据目标节点决定新帖子的 {@code root_id}。</li>
     * </ul>
     */
    private String upsertByRequestId(
            CreateHumanPostCommand command,
            UUID generatedNodeId,
            OffsetDateTime createdAt
    ) {
        return neo4jClient.query(UPSERT_HUMAN_POST_QUERY)
                .bind(command.requestId()).to("requestId")
                .bind(generatedNodeId.toString()).to("nodeId")
                .bind(command.content()).to("content")
                .bind(command.authorId()).to("authorId")
                .bind(command.targetNodeId()).to("targetNodeId")
                .bind(createdAt).to("createdAt")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve node_id from upsert query"));
    }

    private void createAuthoredRelationship(String postNodeId, String authorId, OffsetDateTime createdAt) {
        neo4jClient.query(CREATE_AUTHORED_RELATIONSHIP_QUERY)
                .bind(postNodeId).to("postNodeId")
                .bind(authorId).to("authorId")
                .bind(createdAt).to("createdAt")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create AUTHORED relationship"));
    }

    /**
     * 用于 {@code request_id} 命中既有节点的幂等分支：根据帖子节点上保存的
     * {@code author_id} 直接 MERGE canonical AUTHORED 边。
     *
     * <p>差异于 {@link #createAuthoredRelationship(String, String, OffsetDateTime)}：
     * <ul>
     *   <li>权威来源是节点本身保存的 {@code author_id}，而非传入命令，避免
     *       重放/合谋请求把别人的帖子改写作者归属；</li>
     *   <li>命中既有 AUTHORED 边时 MERGE 不写任何属性，已存在边的 {@code created_at}
     *       与 {@code authored_id} 都保留；</li>
     *   <li>若帖子节点上 {@code author_id} 为空（理论不应发生）或对应账号缺失，
     *       查询返回空结果——按"幂等返回旧节点"语义视为已观测到的脏数据，
     *       不再抛出，由审计端通过漂移报告处理。</li>
     * </ul>
     */
    private void compensateAuthoredRelationship(String postNodeId) {
        neo4jClient.query(COMPENSATE_AUTHORED_RELATIONSHIP_QUERY)
                .bind(postNodeId).to("postNodeId")
                .fetch()
                .one();
    }

    /**
     * 在帖子作为回复时补建 {@code CONTINUES_FROM} 关系。
     *
     * <p>该关系并不是附带信息，而是查询谱系、分支上下文和 AI 路由判断的基础，
     * 所以与帖子节点写入放在同一事务语义下处理。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会向图谱中写一条回复关系边。</li>
     *   <li>当帖子不是回复时直接返回，不产生任何写操作。</li>
     * </ul>
     */
    private void createReplyRelationshipIfNeeded(
            String postNodeId,
            CreateHumanPostCommand command,
            OffsetDateTime createdAt
    ) {
        if (command.targetNodeId() == null) {
            return;
        }
        neo4jClient.query(CREATE_REPLY_RELATIONSHIP_QUERY)
                .bind(postNodeId).to("postNodeId")
                .bind(command.targetNodeId()).to("targetNodeId")
                .bind(HUMAN_OPERATOR_TYPE).to("operatorType")
                .bind(command.authorId()).to("operatorId")
                .bind(createdAt).to("createdAt")
                .bind(USER_REPLY_REASON).to("reason")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to create CONTINUES_FROM relationship"));
    }

    private String findNodeIdByRequestId(String requestId) {
        return neo4jClient.query(FIND_NODE_ID_BY_REQUEST_ID_QUERY)
                .bind(requestId).to("requestId")
                .fetchAs(String.class)
                .one()
                .orElse(null);
    }

    /**
     * 校验回复目标节点是否存在且仍然有效。
     *
     * <p>这一步存在的意义，是在真正写帖子前阻断悬空引用，避免后续链路基于非法父节点继续扩散错误。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会读取 Neo4j。</li>
     *   <li>目标节点不存在时抛出 {@link IllegalArgumentException}。</li>
     * </ul>
     */
    private void validateTargetNodeExists(String targetNodeId) {
        Map<String, Object> result = neo4jClient.query(TARGET_NODE_EXISTS_QUERY)
                .bind(targetNodeId).to("targetNodeId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to validate target_node_id"));
        if (!Boolean.TRUE.equals(result.get("exists"))) {
            throw new IllegalArgumentException("target_node_id not found");
        }
    }

    private void validateAuthorExists(String authorId) {
        Map<String, Object> result = neo4jClient.query(AUTHOR_EXISTS_QUERY)
                .bind(authorId).to("authorId")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to validate author_id"));
        if (!Boolean.TRUE.equals(result.get("exists"))) {
            throw new IllegalArgumentException("author_id not found");
        }
    }

    /**
     * 表示人工帖子创建命令。
     *
     * <p><b>注意事项</b>：
     * <ul>
     *   <li>{@code targetNodeId} 允许为空，表示创建一条新的根帖子。</li>
     *   <li>构造阶段会完成基础文本校验与目标节点 ID 归一化，避免服务层重复处理空白值。</li>
     * </ul>
     */
    public record CreateHumanPostCommand(String requestId, String authorId, String content, String targetNodeId) {
        public CreateHumanPostCommand {
            requestId = requireText(requestId, "requestId");
            authorId = requireText(authorId, "authorId");
            content = requireText(content, "content");
            targetNodeId = normalizeTargetNodeId(targetNodeId);
        }

        public CreateHumanPostCommand(String requestId, String authorId, String content) {
            this(requestId, authorId, content, null);
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value;
        }

        private static String normalizeTargetNodeId(String targetNodeId) {
            if (targetNodeId == null || targetNodeId.isBlank()) {
                return null;
            }
            return targetNodeId;
        }
    }
}
