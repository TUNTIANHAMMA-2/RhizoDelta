package com.rhizodelta.service;

import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.HumanPostRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Service
public class PostService {
    private static final String UPSERT_HUMAN_POST_QUERY = """
            MERGE (post:Human_Post {request_id: $requestId})
            ON CREATE SET
              post.node_id = $nodeId,
              post.content = $content,
              post.author_id = $authorId,
              post.request_id = $requestId,
              post.created_at = $createdAt,
              post.embedding = null
            RETURN toString(post.node_id) AS nodeId
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
                .bind(createdAt).to("createdAt")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve node_id from upsert query"));
    }

    public record CreateHumanPostCommand(String requestId, String authorId, String content) {
        public CreateHumanPostCommand {
            requestId = requireText(requestId, "requestId");
            authorId = requireText(authorId, "authorId");
            content = requireText(content, "content");
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value;
        }
    }
}
