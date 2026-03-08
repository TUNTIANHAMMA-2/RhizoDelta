package com.rhizodelta.domain.node;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    @Relationship(type = "BRANCHED_FROM", direction = Relationship.Direction.OUTGOING)
    private final Set<BranchedFromRelationship> branchedFrom;

    @PersistenceCreator
    public HumanPost(
            UUID nodeId,
            String content,
            String authorId,
            String requestId,
            Instant createdAt,
            @Nullable List<Float> embedding,
            @Nullable Set<BranchedFromRelationship> branchedFrom
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
        this.branchedFrom = branchedFrom == null ? Set.of() : Set.copyOf(branchedFrom);
    }

    public static HumanPost create(UUID nodeId, String content, String authorId, String requestId) {
        return new HumanPost(nodeId, content, authorId, requestId, Instant.now(), null, Set.of());
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

    public Set<BranchedFromRelationship> getBranchedFrom() {
        return Set.copyOf(branchedFrom);
    }
}

enum OperatorType {
    AGENT,
    HUMAN
}

@RelationshipProperties
final class BranchedFromRelationship {
    @Id
    @GeneratedValue
    private final Long id;

    @Property("operator_type")
    private final OperatorType operatorType;

    @Property("operator_id")
    private final String operatorId;

    @Property("created_at")
    private final Instant createdAt;

    @Property("reason")
    private final String reason;

    @TargetNode
    private final HumanPost sourceNode;

    @PersistenceCreator
    BranchedFromRelationship(
            @Nullable Long id,
            OperatorType operatorType,
            String operatorId,
            Instant createdAt,
            String reason,
            HumanPost sourceNode
    ) {
        this.id = id;
        this.operatorType = Objects.requireNonNull(operatorType, "operatorType must not be null");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode must not be null");
    }

    @Nullable
    public Long getId() {
        return id;
    }

    public OperatorType getOperatorType() {
        return operatorType;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReason() {
        return reason;
    }

    public HumanPost getSourceNode() {
        return sourceNode;
    }
}

@RelationshipProperties
final class MergedIntoRelationship {
    @Id
    @GeneratedValue
    private final Long id;

    @Property("operator_type")
    private final OperatorType operatorType;

    @Property("operator_id")
    private final String operatorId;

    @Property("created_at")
    private final Instant createdAt;

    @Property("reason")
    private final String reason;

    @TargetNode
    private final AIConsensus targetNode;

    @PersistenceCreator
    MergedIntoRelationship(
            @Nullable Long id,
            OperatorType operatorType,
            String operatorId,
            Instant createdAt,
            String reason,
            AIConsensus targetNode
    ) {
        this.id = id;
        this.operatorType = Objects.requireNonNull(operatorType, "operatorType must not be null");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode must not be null");
    }

    @Nullable
    public Long getId() {
        return id;
    }

    public OperatorType getOperatorType() {
        return operatorType;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReason() {
        return reason;
    }

    public AIConsensus getTargetNode() {
        return targetNode;
    }
}

@RelationshipProperties
final class SynthesizedFromRelationship {
    @Id
    @GeneratedValue
    private final Long id;

    @Property("operator_type")
    private final OperatorType operatorType;

    @Property("operator_id")
    private final String operatorId;

    @Property("created_at")
    private final Instant createdAt;

    @Property("reason")
    private final String reason;

    @TargetNode
    private final HumanPost sourceNode;

    @PersistenceCreator
    SynthesizedFromRelationship(
            @Nullable Long id,
            OperatorType operatorType,
            String operatorId,
            Instant createdAt,
            String reason,
            HumanPost sourceNode
    ) {
        this.id = id;
        this.operatorType = Objects.requireNonNull(operatorType, "operatorType must not be null");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode must not be null");
    }

    @Nullable
    public Long getId() {
        return id;
    }

    public OperatorType getOperatorType() {
        return operatorType;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReason() {
        return reason;
    }

    public HumanPost getSourceNode() {
        return sourceNode;
    }
}
