package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 当前用户自身画像的对外视图。
 *
 * <p>所有非 id 字段允许为 {@code null}，表示画像尚未设置该属性。
 * 画像尚未创建（缺失 HAS_PROFILE 边）的情况下，{@code display_name}
 * 会回退为 {@code username}，其余字段为 {@code null}。
 */
public record UserProfilePayload(
        @JsonProperty("user_id") String userId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("language") String language,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("theme") String theme,
        @JsonProperty("notification_prefs") String notificationPrefs,
        @JsonProperty("updated_at") String updatedAt
) {
}
