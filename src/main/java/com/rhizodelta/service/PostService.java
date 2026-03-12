package com.rhizodelta.service;

import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.HumanPostRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PostService {
    private static final String UPSERT_HUMAN_POST_QUERY = """
            MERGE (post:Human_Post {request_id: $requestId})
            ON CREATE SET
              post:GraphNode,
              post.node_id = $nodeId,
              post.content = $content,
              post.author_id = $authorId,
              post.request_id = $requestId,
              post.target_node_id = $targetNodeId,
              post.created_at = $createdAt,
              post.embedding = null
            RETURN toString(post.node_id) AS nodeId
            """;

    private static final String FIND_NODE_ID_BY_REQUEST_ID_QUERY = """
            MATCH (post:Human_Post {request_id: $requestId})
            RETURN toString(post.node_id) AS nodeId
            """;

    private static final String TARGET_NODE_EXISTS_QUERY = """
            MATCH (node:GraphNode {node_id: $targetNodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN count(node) > 0 AS exists
            """;

    private final Neo4jClient neo4jClient;
    private final HumanPostRepository humanPostRepository;

    public PostService(Neo4jClient neo4jClient, HumanPostRepository humanPostRepository) {
        this.neo4jClient = neo4jClient;
        this.humanPostRepository = humanPostRepository;
    }

    @Transactional(transactionManager = "transactionManager")
    public HumanPost createHumanPost(CreateHumanPostCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        String existingNodeId = findNodeIdByRequestId(command.requestId());
        if (existingNodeId != null) {
            return humanPostRepository.findByNodeId(UUID.fromString(existingNodeId))
                    .orElseThrow(() -> new IllegalStateException("Human_Post not found after upsert"));
        }

        if (command.targetNodeId() != null) {
            validateTargetNodeExists(command.targetNodeId());
        }

        UUID generatedNodeId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        String nodeIdString = upsertByRequestId(command, generatedNodeId, createdAt);
        UUID persistedNodeId = UUID.fromString(nodeIdString);

        return humanPostRepository.findByNodeId(persistedNodeId)
                .orElseThrow(() -> new IllegalStateException("Human_Post not found after upsert"));
    }

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

    private String findNodeIdByRequestId(String requestId) {
        return neo4jClient.query(FIND_NODE_ID_BY_REQUEST_ID_QUERY)
                .bind(requestId).to("requestId")
                .fetchAs(String.class)
                .one()
                .orElse(null);
    }

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
