package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.core.domain.association.AssociationInfo;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.core.service.AssociationService;
import com.rhizodelta.query.service.NodeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/nodes")
public class NodeQueryController {
    private final NodeQueryService nodeQueryService;
    private final AssociationService associationService;

    public NodeQueryController(NodeQueryService nodeQueryService, AssociationService associationService) {
        this.nodeQueryService = nodeQueryService;
        this.associationService = associationService;
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

    private NodePayload fromLineageNode(NodeQueryService.LineageNode node) {
        return new NodePayload(
                node.nodeId(),
                node.label(),
                node.content(),
                node.summaryContent(),
                node.authorId(),
                node.agentVersion(),
                node.createdAt(),
                node.hasEmbedding(),
                node.qualityOverall()
        );
    }

    private EdgePayload fromLineageEdge(NodeQueryService.LineageEdge edge) {
        return new EdgePayload(edge.source(), edge.target(), edge.type(), edge.createdAt());
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodePayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("label") String label,
            @JsonProperty("content") String content,
            @JsonProperty("summary_content") String summaryContent,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("agent_version") String agentVersion,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("has_embedding") boolean hasEmbedding,
            @JsonProperty("quality_overall") Double qualityOverall
    ) {
    }
}
