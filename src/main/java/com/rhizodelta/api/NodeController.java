package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.association.AssociationInfo;
import com.rhizodelta.domain.embedding.EmbeddingWriteRequest;
import com.rhizodelta.domain.embedding.EmbeddingWriteResult;
import com.rhizodelta.domain.embedding.SimilaritySearchRequest;
import com.rhizodelta.domain.embedding.SimilaritySearchResult;
import com.rhizodelta.domain.ai.SummaryResult;
import com.rhizodelta.service.AssociationService;
import com.rhizodelta.domain.association.AssociationType;
import com.rhizodelta.service.EmbeddingService;
import com.rhizodelta.service.NodeQueryService;
import com.rhizodelta.service.SummaryAgentService;
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
    private final NodeQueryService nodeQueryService;
    private final AssociationService associationService;
    private final EmbeddingService embeddingService;
    private final SummaryAgentService summaryAgentService;

    public NodeController(
            NodeQueryService nodeQueryService,
            AssociationService associationService,
            EmbeddingService embeddingService,
            SummaryAgentService summaryAgentService
    ) {
        this.nodeQueryService = nodeQueryService;
        this.associationService = associationService;
        this.embeddingService = embeddingService;
        this.summaryAgentService = summaryAgentService;
    }

    @GetMapping("/roots")
    public ApiResponse<List<NodePayload>> getRoots(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        List<NodePayload> roots = nodeQueryService.getRoots(limit).stream()
                .map(this::fromLineageNode)
                .toList();
        return ApiResponse.ok(roots);
    }

    @GetMapping("/{id}")
    public ApiResponse<NodePayload> getNodeById(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        NodeQueryService.LineageNode node = nodeQueryService.getNodeSummaryById(nodeId);
        return ApiResponse.ok(fromLineageNode(node));
    }

    @PutMapping("/{id}/embedding")
    public ApiResponse<EmbeddingWriteResult> putEmbedding(
            @PathVariable("id") String id,
            @RequestBody EmbeddingWriteRequest request
    ) {
        EmbeddingWriteResult result = embeddingService.writeEmbedding(id, request.vector());
        return ApiResponse.ok(result);
    }

    // POST is used instead of GET because the vector payload may exceed URL length limits.
    // This operation is idempotent and read-only.
    @PostMapping("/search/similar")
    public ApiResponse<List<SimilaritySearchResult>> searchSimilar(
            @RequestBody SimilaritySearchRequest request
    ) {
        List<SimilaritySearchResult> results = embeddingService.searchSimilar(request.vector(), request.top_k());
        return ApiResponse.ok(results);
    }

    @GetMapping("/{id}/lineage")
    public ApiResponse<GraphTopologyResponse> getLineage(
            @PathVariable("id") String id,
            @RequestParam(value = "max_depth", required = false) Integer maxDepth
    ) {
        UUID nodeId = parseUuid(id);
        NodeQueryService.GraphTopology topology = nodeQueryService.getLineageTopology(nodeId, maxDepth);
        List<NodePayload> nodes = topology.nodes().stream()
                .map(this::fromLineageNode)
                .toList();
        List<EdgePayload> edges = topology.edges().stream()
                .map(this::fromLineageEdge)
                .toList();
        return ApiResponse.ok(new GraphTopologyResponse(nodes, edges));
    }

    @GetMapping("/{id}/children")
    public ApiResponse<GraphTopologyResponse> getChildren(
            @PathVariable("id") String id,
            @RequestParam(value = "max_depth", required = false) Integer maxDepth,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        UUID nodeId = parseUuid(id);
        NodeQueryService.GraphTopology topology = nodeQueryService.getChildrenTopology(nodeId, maxDepth, limit);
        List<NodePayload> nodes = topology.nodes().stream()
                .map(this::fromLineageNode)
                .toList();
        List<EdgePayload> edges = topology.edges().stream()
                .map(this::fromLineageEdge)
                .toList();
        return ApiResponse.ok(new GraphTopologyResponse(nodes, edges));
    }

    @GetMapping("/{id}/provenance")
    public ApiResponse<List<NodePayload>> getProvenance(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        List<NodePayload> provenance = nodeQueryService.getProvenanceSummaries(nodeId)
                .stream()
                .map(this::fromLineageNode)
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

    @PostMapping("/{id}/summarize")
    public ApiResponse<SummaryResult> summarize(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        SummaryResult result = summaryAgentService.generate(nodeId);
        return ApiResponse.ok(result);
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

    private EdgePayload fromLineageEdge(NodeQueryService.LineageEdge edge) {
        return new EdgePayload(
                edge.source(),
                edge.target(),
                edge.type(),
                edge.createdAt()
        );
    }

    private static UUID parseUuid(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("id must be a valid UUID", exception);
        }
    }

    public record GraphTopologyResponse(
            @JsonProperty("nodes") List<NodePayload> nodes,
            @JsonProperty("edges") List<EdgePayload> edges
    ) {
    }

    public record EdgePayload(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("type") String type,
            @JsonProperty("created_at") Instant createdAt
    ) {
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
