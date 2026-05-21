package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 表示锚定在讨论树评论区域下的 AI 共识或结果注记。
 */
public record DiscussionArtifact(
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("kind") String kind,
        @JsonProperty("anchor_node_id") String anchorNodeId,
        @JsonProperty("body") String body,
        @JsonProperty("source_node_ids") List<String> sourceNodeIds,
        @JsonProperty("source_count") int sourceCount,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("agent_version") String agentVersion
) {
    public DiscussionArtifact {
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
    }
}
