package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示讨论树响应的查询参数与截断状态。
 */
public record DiscussionTreeMeta(
        @JsonProperty("root_node_id") String rootNodeId,
        @JsonProperty("max_depth") int maxDepth,
        @JsonProperty("limit") int limit,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_cursor") String nextCursor
) {
}
