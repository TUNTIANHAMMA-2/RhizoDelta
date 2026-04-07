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
