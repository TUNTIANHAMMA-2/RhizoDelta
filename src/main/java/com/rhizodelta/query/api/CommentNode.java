package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 表示讨论树中的一个 Human_Post 评论节点。
 */
public record CommentNode(
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("content") String content,
        @JsonProperty("author") CommentAuthor author,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("depth") int depth,
        @JsonProperty("children") List<CommentNode> children,
        @JsonProperty("artifacts") List<DiscussionArtifact> artifacts,
        @JsonProperty("has_more_children") boolean hasMoreChildren,
        @JsonProperty("total_children_count") int totalChildrenCount
) {
    public CommentNode {
        children = children == null ? List.of() : List.copyOf(children);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
