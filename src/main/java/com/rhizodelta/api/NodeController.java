package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.domain.association.AssociationInfo;
import com.rhizodelta.domain.embedding.EmbeddingWriteRequest;
import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.domain.embedding.SimilaritySearchRequest;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.service.AssociationService;
import com.rhizodelta.domain.association.AssociationType;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.NodeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/nodes")
public class NodeController {
    private static final String HUMAN_POST_LABEL = "Human_Post";
    private static final String AI_CONSENSUS_LABEL = "AI_Consensus";

    private final NodeQueryService nodeQueryService;
    private final AssociationService associationService;
    private final EmbeddingService embeddingService;

    public NodeController(
            NodeQueryService nodeQueryService,
            AssociationService associationService,
            EmbeddingService embeddingService
    ) {
        this.nodeQueryService = nodeQueryService;
        this.associationService = associationService;
        this.embeddingService = embeddingService;
    }

    @GetMapping("/{id}")
    public ApiResponse<NodePayload> getNodeById(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        NodeQueryService.NodeResult result = nodeQueryService.getNodeById(nodeId);
        return ApiResponse.ok(toPayload(result));
    }

    @PutMapping("/{id}/embedding")
    public ApiResponse<EmbeddingWriteResult> putEmbedding(
            @PathVariable("id") String id,
            @RequestBody EmbeddingWriteRequest request
    ) {
        EmbeddingWriteResult result = embeddingService.writeEmbedding(id, request.vector());
        return ApiResponse.ok(result);
    }

    @PostMapping("/search/similar")
    public ApiResponse<List<SimilaritySearchResult>> searchSimilar(
            @RequestBody SimilaritySearchRequest request
    ) {
        List<SimilaritySearchResult> results = embeddingService.searchSimilar(request.vector(), request.top_k());
        return ApiResponse.ok(results);
    }

    @GetMapping("/{id}/lineage")
    public ApiResponse<List<NodePayload>> getLineage(
            @PathVariable("id") String id,
            @RequestParam(value = "max_depth", required = false) Integer maxDepth
    ) {
        UUID nodeId = parseUuid(id);
        List<NodePayload> lineage = nodeQueryService.getLineage(nodeId, maxDepth)
                .stream()
                .map(this::fromLineageNode)
                .toList();
        return ApiResponse.ok(lineage);
    }

    @GetMapping("/{id}/provenance")
    public ApiResponse<List<NodePayload>> getProvenance(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        List<NodePayload> provenance = nodeQueryService.getProvenance(nodeId)
                .stream()
                .map(this::fromHumanPost)
                .toList();
        return ApiResponse.ok(provenance);
    }

    @GetMapping("/{id}/associations")
    public ApiResponse<List<AssociationInfo>> getAssociations(
            @PathVariable("id") String id,
            @RequestParam(value = "type", required = false) AssociationType type,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        UUID nodeId = parseUuid(id);
        List<AssociationInfo> associations = associationService.findAssociationsByNodeId(nodeId, type, limit);
        return ApiResponse.ok(associations);
    }

    private NodePayload toPayload(NodeQueryService.NodeResult result) {
        if (result instanceof NodeQueryService.HumanPostNode humanPostNode) {
            return fromHumanPost(humanPostNode.node());
        }
        if (result instanceof NodeQueryService.AIConsensusNode aiConsensusNode) {
            return fromAIConsensus(aiConsensusNode.node());
        }
        throw new IllegalStateException("Unsupported node result type: " + result.getClass().getName());
    }

    private NodePayload fromHumanPost(HumanPost node) {
        return new NodePayload(
                node.getNodeId().toString(),
                HUMAN_POST_LABEL,
                node.getContent(),
                null,
                node.getAuthorId(),
                null,
                node.getCreatedAt(),
                node.getEmbedding() != null
        );
    }

    private NodePayload fromAIConsensus(AIConsensus node) {
        return new NodePayload(
                node.getNodeId().toString(),
                AI_CONSENSUS_LABEL,
                null,
                node.getSummaryContent(),
                null,
                node.getAgentVersion(),
                node.getCreatedAt(),
                node.getEmbedding() != null
        );
    }

    private NodePayload fromLineageNode(NodeQueryService.LineageNode node) {
        return new NodePayload(
                node.nodeId(),
                node.label(),
                node.content(),
                node.summaryContent(),
                node.authorId(),
                node.agentVersion(),
                node.createdAt(),
                node.hasEmbedding()
        );
    }

    private static UUID parseUuid(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("id must be a valid UUID", exception);
        }
    }

    public record NodePayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("label") String label,
            @JsonProperty("content") String content,
            @JsonProperty("summary_content") String summaryContent,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("agent_version") String agentVersion,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("has_embedding") boolean hasEmbedding
    ) {
    }
}
