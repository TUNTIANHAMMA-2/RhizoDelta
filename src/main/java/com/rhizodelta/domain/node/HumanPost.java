package com.rhizodelta.domain.node;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Node("Human_Post")
public final class HumanPost {
    @Id
    @Property("node_id")
    private final UUID nodeId;

    @Property("content")
    private final String content;

    @Property("author_id")
    private final String authorId;

    @Property("created_at")
    private final Instant createdAt;

    @Nullable
    @Property("embedding")
    private final List<Float> embedding;

    @PersistenceCreator
    public HumanPost(
            UUID nodeId,
            String content,
            String authorId,
            Instant createdAt,
            @Nullable List<Float> embedding
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
    }

    public static HumanPost create(UUID nodeId, String content, String authorId) {
        return new HumanPost(nodeId, content, authorId, Instant.now(), null);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public List<Float> getEmbedding() {
        return embedding == null ? null : List.copyOf(embedding);
    }
}
