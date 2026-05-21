package com.rhizodelta.query.service;

import com.rhizodelta.query.api.CommentAuthor;
import com.rhizodelta.query.api.CommentNode;
import com.rhizodelta.query.api.DiscussionArtifact;
import com.rhizodelta.query.api.DiscussionTreeMeta;
import com.rhizodelta.query.api.DiscussionTreeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 将节点子树拓扑投影为移动端可直接阅读的嵌套讨论树。
 */
@Service
public class DiscussionTreeQueryService {
    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final int MIN_MAX_DEPTH = 1;
    private static final int MAX_MAX_DEPTH = 10;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 500;

    private static final String HUMAN_POST = "Human_Post";
    private static final String AI_CONSENSUS = "AI_Consensus";
    private static final String RESULT = "Result";
    private static final String CONTINUES_FROM = "CONTINUES_FROM";
    private static final String BRANCHED_FROM = "BRANCHED_FROM";
    private static final String MERGED_INTO = "MERGED_INTO";
    private static final String SYNTHESIZED_FROM = "SYNTHESIZED_FROM";
    private static final String MATERIALIZED_FROM = "MATERIALIZED_FROM";

    private final NodeQueryService nodeQueryService;

    public DiscussionTreeQueryService(NodeQueryService nodeQueryService) {
        this.nodeQueryService = nodeQueryService;
    }

    /**
     * 返回以 Human_Post 为根的嵌套讨论树。
     *
     * @param rootId 根帖节点 ID。
     * @param maxDepth 可选最大深度，范围 1..10，默认 5。
     * @param limit 可选可见 Human_Post 节点数，范围 1..500，默认 200。
     * @param callerUserId 当前调用方用户 ID，透传给底层查询以便保留调用方上下文。
     * @param cursor MVP 暂不支持分页游标，非空会抛出 {@link UnsupportedOperationException}。
     * @return 讨论树响应。
     */
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public DiscussionTreeResponse getDiscussionTree(UUID rootId,
                                                    Integer maxDepth,
                                                    Integer limit,
                                                    String callerUserId,
                                                    String cursor) {
        Objects.requireNonNull(rootId, "rootId must not be null");
        if (cursor != null && !cursor.isBlank()) {
            throw new UnsupportedOperationException("discussion-tree cursor pagination is not supported yet");
        }

        int resolvedMaxDepth = resolveMaxDepth(maxDepth);
        int resolvedLimit = resolveLimit(limit);
        NodeQueryService.GraphTopology topology = nodeQueryService.getChildrenTopology(rootId, resolvedMaxDepth, null);
        TransformResult transformed = transformToTree(topology, rootId.toString(), resolvedLimit);
        DiscussionTreeMeta meta = new DiscussionTreeMeta(
                rootId.toString(),
                resolvedMaxDepth,
                resolvedLimit,
                transformed.truncated(),
                transformed.truncated(),
                encodeCursor()
        );
        return new DiscussionTreeResponse(transformed.root(), meta);
    }

    private int resolveMaxDepth(Integer maxDepth) {
        if (maxDepth == null) {
            return DEFAULT_MAX_DEPTH;
        }
        if (maxDepth < MIN_MAX_DEPTH || maxDepth > MAX_MAX_DEPTH) {
            throw new IllegalArgumentException("max_depth must be between 1 and 10");
        }
        return maxDepth;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return limit;
    }

    private String encodeCursor() {
        return null;
    }

    private TransformResult transformToTree(NodeQueryService.GraphTopology topology, String rootId, int limit) {
        Map<String, NodeQueryService.LineageNode> nodesById = indexNodes(topology.nodes());
        NodeQueryService.LineageNode rootNode = nodesById.get(rootId);
        if (rootNode == null) {
            throw new NoSuchElementException("Node not found: " + rootId);
        }
        if (!HUMAN_POST.equals(rootNode.label())) {
            throw new IllegalArgumentException("discussion tree root must be a Human_Post node");
        }

        TreeIndexes indexes = buildTreeIndexes(topology.edges(), nodesById);
        sortChildren(indexes.childrenByParent(), nodesById);
        LinkedHashSet<String> visibleNodeIds = collectVisibleBackboneNodes(rootId, limit, indexes.childrenByParent(), nodesById);
        boolean truncated = visibleNodeIds.size() < countReachableBackboneNodes(rootId, indexes.childrenByParent(), nodesById);
        CommentNode root = buildCommentNode(rootId, null, 0, nodesById, indexes, visibleNodeIds);
        return new TransformResult(root, truncated);
    }

    private Map<String, NodeQueryService.LineageNode> indexNodes(List<NodeQueryService.LineageNode> nodes) {
        Map<String, NodeQueryService.LineageNode> index = new LinkedHashMap<>();
        for (NodeQueryService.LineageNode node : nodes) {
            if (node.nodeId() != null) {
                index.put(node.nodeId(), node);
            }
        }
        return index;
    }

    private TreeIndexes buildTreeIndexes(List<NodeQueryService.LineageEdge> edges,
                                         Map<String, NodeQueryService.LineageNode> nodesById) {
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        Map<String, List<ArtifactAnchor>> artifactAnchors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> artifactSources = new LinkedHashMap<>();

        for (NodeQueryService.LineageEdge edge : edges) {
            if (edge.source() == null || edge.target() == null || edge.type() == null) {
                continue;
            }
            switch (edge.type()) {
                case CONTINUES_FROM, BRANCHED_FROM -> appendBackboneChild(childrenByParent, nodesById, edge.source(), edge.target());
                case MERGED_INTO -> appendArtifactAnchor(artifactAnchors, edge.target(), edge.source(), "CONSENSUS");
                case MATERIALIZED_FROM -> appendArtifactAnchor(artifactAnchors, edge.target(), edge.source(), "RESULT");
                case SYNTHESIZED_FROM -> artifactSources
                        .computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>())
                        .add(edge.target());
                default -> {
                    // CROSS_SYNTHESIZED_FROM / CONVERGED_FROM 等关系在移动端 MVP 中不进入讨论树。
                }
            }
        }

        return new TreeIndexes(childrenByParent, artifactAnchors, artifactSources);
    }

    private void appendBackboneChild(Map<String, List<String>> childrenByParent,
                                     Map<String, NodeQueryService.LineageNode> nodesById,
                                     String childId,
                                     String parentId) {
        NodeQueryService.LineageNode child = nodesById.get(childId);
        NodeQueryService.LineageNode parent = nodesById.get(parentId);
        if (child == null || parent == null || !HUMAN_POST.equals(child.label()) || !HUMAN_POST.equals(parent.label())) {
            return;
        }
        List<String> children = childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>());
        if (!children.contains(childId)) {
            children.add(childId);
        }
    }

    private void appendArtifactAnchor(Map<String, List<ArtifactAnchor>> artifactAnchors,
                                      String anchorNodeId,
                                      String artifactNodeId,
                                      String kind) {
        List<ArtifactAnchor> anchors = artifactAnchors.computeIfAbsent(anchorNodeId, ignored -> new ArrayList<>());
        ArtifactAnchor next = new ArtifactAnchor(artifactNodeId, kind);
        if (!anchors.contains(next)) {
            anchors.add(next);
        }
    }

    private void sortChildren(Map<String, List<String>> childrenByParent,
                              Map<String, NodeQueryService.LineageNode> nodesById) {
        Comparator<String> childComparator = Comparator
                .comparing((String nodeId) -> nullableInstant(nodesById.get(nodeId).createdAt()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.naturalOrder());
        childrenByParent.values().forEach(children -> children.sort(childComparator));
    }

    private Instant nullableInstant(Instant instant) {
        return instant;
    }

    private LinkedHashSet<String> collectVisibleBackboneNodes(String rootId,
                                                              int limit,
                                                              Map<String, List<String>> childrenByParent,
                                                              Map<String, NodeQueryService.LineageNode> nodesById) {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(rootId);
        queue.add(rootId);

        while (!queue.isEmpty() && visited.size() < limit) {
            String current = queue.removeFirst();
            for (String childId : childrenByParent.getOrDefault(current, List.of())) {
                NodeQueryService.LineageNode child = nodesById.get(childId);
                if (child == null || !HUMAN_POST.equals(child.label()) || !visited.add(childId)) {
                    continue;
                }
                queue.addLast(childId);
                if (visited.size() >= limit) {
                    break;
                }
            }
        }
        return visited;
    }

    private int countReachableBackboneNodes(String rootId,
                                            Map<String, List<String>> childrenByParent,
                                            Map<String, NodeQueryService.LineageNode> nodesById) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String childId : childrenByParent.getOrDefault(current, List.of())) {
                NodeQueryService.LineageNode child = nodesById.get(childId);
                if (child == null || !HUMAN_POST.equals(child.label()) || !visited.add(childId)) {
                    continue;
                }
                queue.addLast(childId);
            }
        }
        return visited.size();
    }

    private CommentNode buildCommentNode(String nodeId,
                                         String parentId,
                                         int depth,
                                         Map<String, NodeQueryService.LineageNode> nodesById,
                                         TreeIndexes indexes,
                                         Set<String> visibleNodeIds) {
        NodeQueryService.LineageNode node = nodesById.get(nodeId);
        List<String> allChildren = indexes.childrenByParent().getOrDefault(nodeId, List.of());
        List<String> visibleChildren = allChildren.stream()
                .filter(visibleNodeIds::contains)
                .toList();
        List<CommentNode> children = visibleChildren.stream()
                .map(childId -> buildCommentNode(childId, nodeId, depth + 1, nodesById, indexes, visibleNodeIds))
                .toList();
        List<DiscussionArtifact> artifacts = buildArtifacts(nodeId, nodesById, indexes);
        int totalChildrenCount = allChildren.size();
        boolean hasMoreChildren = visibleChildren.size() < totalChildrenCount;

        return new CommentNode(
                node.nodeId(),
                node.content(),
                new CommentAuthor(node.authorId(), node.authorUsername(), node.authorDisplayName()),
                node.createdAt(),
                parentId,
                depth,
                children,
                artifacts,
                hasMoreChildren,
                totalChildrenCount
        );
    }

    private List<DiscussionArtifact> buildArtifacts(String anchorNodeId,
                                                    Map<String, NodeQueryService.LineageNode> nodesById,
                                                    TreeIndexes indexes) {
        List<ArtifactAnchor> anchors = indexes.artifactAnchors().getOrDefault(anchorNodeId, List.of());
        if (anchors.isEmpty()) {
            return List.of();
        }
        List<DiscussionArtifact> artifacts = new ArrayList<>(anchors.size());
        for (ArtifactAnchor anchor : anchors) {
            NodeQueryService.LineageNode node = nodesById.get(anchor.nodeId());
            if (node == null) {
                continue;
            }
            String body = "CONSENSUS".equals(anchor.kind()) ? node.summaryContent() : node.content();
            List<String> sourceNodeIds = "CONSENSUS".equals(anchor.kind())
                    ? sortedArtifactSourceNodeIds(anchor.nodeId(), nodesById, indexes)
                    : List.of();
            artifacts.add(new DiscussionArtifact(
                    node.nodeId(),
                    anchor.kind(),
                    anchorNodeId,
                    body,
                    sourceNodeIds,
                    sourceNodeIds.size(),
                    node.createdAt(),
                    node.agentVersion()
            ));
        }
        artifacts.sort(Comparator
                .comparing(DiscussionArtifact::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DiscussionArtifact::nodeId));
        return List.copyOf(artifacts);
    }

    private List<String> sortedArtifactSourceNodeIds(String artifactNodeId,
                                                     Map<String, NodeQueryService.LineageNode> nodesById,
                                                     TreeIndexes indexes) {
        LinkedHashSet<String> sourceIds = indexes.artifactSources().getOrDefault(artifactNodeId, new LinkedHashSet<>());
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return sourceIds.stream()
                .sorted(Comparator
                        .comparing((String sourceId) -> {
                            NodeQueryService.LineageNode sourceNode = nodesById.get(sourceId);
                            return sourceNode == null ? null : sourceNode.createdAt();
                        }, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private record TreeIndexes(
            Map<String, List<String>> childrenByParent,
            Map<String, List<ArtifactAnchor>> artifactAnchors,
            Map<String, LinkedHashSet<String>> artifactSources
    ) {
    }

    private record ArtifactAnchor(String nodeId, String kind) {
    }

    private record TransformResult(CommentNode root, boolean truncated) {
    }
}
