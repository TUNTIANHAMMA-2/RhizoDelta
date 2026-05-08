package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.core.domain.association.AssociationInfo;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.core.service.AssociationService;
import com.rhizodelta.query.service.NodeQueryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 提供图谱节点的只读查询接口。
 *
 * <p>该控制器位于 {@code com.rhizodelta.query.api}，负责把节点摘要、谱系拓扑、子树拓扑、
 * 溯源信息与业务关联信息统一暴露给上层调用方。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>所有接口均为只读，不直接写数据库、不发布消息。</li>
 *   <li>入口层统一校验 UUID，避免非法节点 ID 进入服务层。</li>
 *   <li>拓扑接口会把服务层的统一节点视图转换成前端可直接消费的节点/边响应结构。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/nodes")
public class NodeQueryController {
    private final NodeQueryService nodeQueryService;
    private final AssociationService associationService;
    private final PreferenceEventService preferenceEventService;

    public NodeQueryController(NodeQueryService nodeQueryService, AssociationService associationService, PreferenceEventService preferenceEventService) {
        this.nodeQueryService = nodeQueryService;
        this.associationService = associationService;
        this.preferenceEventService = preferenceEventService;
    }

    /**
     * 返回根节点摘要列表。
     *
     * <p>该接口通常作为图谱入口导航使用，调用方无需理解根节点判定规则。
     *
     * <p>
     *
     * @param limit 可选返回数量。
     * @return 根节点摘要列表。
     */
    @GetMapping("/roots")
    public ApiResponse<List<NodePayload>> getRoots(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        List<NodePayload> roots = nodeQueryService.getRoots(limit).stream()
                .map(this::fromLineageNode)
                .toList();
        return ApiResponse.ok(roots);
    }

    /**
     * 返回单个节点的统一摘要。
     *
     * <p>该接口适合详情页头部或轻量卡片场景，不负责展开整张图。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @return 节点摘要。
     */
    @GetMapping("/{id}")
    public ApiResponse<NodePayload> getNodeById(@PathVariable("id") String id, Authentication authentication) {
        UUID nodeId = parseUuid(id);
        NodeQueryService.LineageNode node = nodeQueryService.getNodeSummaryById(nodeId);
        recordViewEvent(authentication, nodeId);
        return ApiResponse.ok(fromLineageNode(node));
    }

    /**
     * 返回节点向上的谱系拓扑。
     *
     * <p>该接口用于展示一个节点如何沿着版本演化关系回溯到其祖先，并附带相关共识节点。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @param maxDepth 可选最大深度。
     * @return 谱系拓扑。
     */
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

    /**
     * 返回节点向下的子树拓扑。
     *
     * <p>该接口会把回复、分支、共识与结果层节点一起纳入返回视图，
     * 适合“从当前节点继续往后看”的查询场景。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @param maxDepth 可选最大深度。
     * @param limit 可选节点数量限制。
     * @return 子树拓扑。
     */
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

    /**
     * 返回任意节点的直接上游摘要列表。
     *
     * <p>查询深度固定为 1 跳。按节点类型返回不同的关系语义：
     * <ul>
     *   <li>{@code AI_Consensus} → 沿 {@code SYNTHESIZED_FROM} 返回合成来源；</li>
     *   <li>{@code Human_Post} → 沿 {@code CONTINUES_FROM} 或 {@code BRANCHED_FROM} 返回父节点；</li>
     *   <li>{@code Result} → 沿 {@code MATERIALIZED_FROM} 返回物化来源。</li>
     * </ul>
     *
     * <p>对没有上游的节点（例如根帖、无来源的独立 Result）返回空列表。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @return 直接上游节点摘要列表。
     */
    @GetMapping("/{id}/provenance")
    public ApiResponse<List<NodePayload>> getProvenance(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        List<NodePayload> provenance = nodeQueryService.getProvenanceSummaries(nodeId)
                .stream()
                .map(this::fromLineageNode)
                .toList();
        return ApiResponse.ok(provenance);
    }

    /**
     * 返回节点的业务关联关系。
     *
     * <p>该接口与谱系查询不同，关注的是语义关联而非版本演化关系。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @param type 可选关联类型过滤条件。
     * @param limit 可选数量限制。
     * @return 关联列表。
     */
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
                node.authorUsername(),
                node.authorDisplayName(),
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

    private void recordViewEvent(Authentication authentication, UUID nodeId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser u)) {
            return;
        }
        preferenceEventService.recordEvent(u.sub(), null, "VIEW", 0.5, nodeId.toString());
    }

    /**
     * 表示拓扑查询的统一响应结构。
     *
     * <p>该对象把节点集与边集显式拆开，便于图组件直接消费。
     */
    public record GraphTopologyResponse(
            @JsonProperty("nodes") List<NodePayload> nodes,
            @JsonProperty("edges") List<EdgePayload> edges
    ) {
    }

    /**
     * 表示一条用于前端展示的拓扑边。
     */
    public record EdgePayload(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("type") String type,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * 表示统一的节点展示载荷。
     *
     * <p>该对象屏蔽了底层帖子、共识、结果节点之间的差异，便于前端统一渲染。
     */
    public record NodePayload(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("label") String label,
            @JsonProperty("content") String content,
            @JsonProperty("summary_content") String summaryContent,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("author_username") String authorUsername,
            @JsonProperty("author_display_name") String authorDisplayName,
            @JsonProperty("agent_version") String agentVersion,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("has_embedding") boolean hasEmbedding,
            @JsonProperty("quality_overall") Double qualityOverall
    ) {
    }
}
