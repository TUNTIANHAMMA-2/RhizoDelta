package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示移动端讨论树端点的响应主体。
 */
public record DiscussionTreeResponse(
        @JsonProperty("root") CommentNode root,
        @JsonProperty("meta") DiscussionTreeMeta meta
) {
}
