package com.rhizodelta.domain.node;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    @Relationship(type = "MERGED_INTO", direction = Relationship.Direction.OUTGOING)
    private final Set<MergedIntoRelationship> mergedInto;

    @Relationship(type = "SYNTHESIZED_FROM", direction = Relationship.Direction.OUTGOING)
    private final Set<SynthesizedFromRelationship> synthesizedFrom;

    @PersistenceCreator
    public AIConsensus(
            UUID nodeId,
            String summaryContent,
            String agentVersion,
            Instant createdAt,
            @Nullable List<Float> embedding,
            @Nullable Set<MergedIntoRelationship> mergedInto,
            @Nullable Set<SynthesizedFromRelationship> synthesizedFrom
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.summaryContent = Objects.requireNonNull(summaryContent, "summaryContent must not be null");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.embedding = embedding == null ? null : List.copyOf(embedding);
        this.mergedInto = mergedInto == null ? Set.of() : Set.copyOf(mergedInto);
        this.synthesizedFrom = synthesizedFrom == null ? Set.of() : Set.copyOf(synthesizedFrom);
    }

    public static AIConsensus create(UUID nodeId, String summaryContent, String agentVersion) {
        return new AIConsensus(nodeId, summaryContent, agentVersion, Instant.now(), null, Set.of(), Set.of());
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

    public Set<MergedIntoRelationship> getMergedInto() {
        return Set.copyOf(mergedInto);
    }

    public Set<SynthesizedFromRelationship> getSynthesizedFrom() {
        return Set.copyOf(synthesizedFrom);
    }
}
