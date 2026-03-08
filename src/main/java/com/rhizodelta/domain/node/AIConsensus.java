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

@Node("AI_Consensus")
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
