package com.rhizodelta.query.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示讨论树评论作者的轻量展示信息。
 */
public record CommentAuthor(
        @JsonProperty("user_id") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("display_name") String displayName
) {
}
