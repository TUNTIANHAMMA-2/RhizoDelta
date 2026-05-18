package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 公开（被他人查看）的用户画像视图。
 *
 * <p>{@code SUSPENDED} / {@code DELETED} 账号下，所有可识别字段会被遮蔽为 {@code null}，
 * 仅返回 {@code user_id} + {@code status = "UNAVAILABLE"}；正常账号则展开全部字段。
 *
 * <p>使用 {@link JsonInclude.Include#NON_NULL}：被遮蔽的字段（null）会从响应 JSON 中省略，
 * 与遮蔽前端约定保持一致（前端只能看到 user_id + status，不能通过键存在性反推被遮蔽用户的属性）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicUserProfilePayload(
        @JsonProperty("user_id") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("status") String status
) {
    public static PublicUserProfilePayload unavailable(String userId) {
        return new PublicUserProfilePayload(userId, null, null, null, "UNAVAILABLE");
    }

    public static PublicUserProfilePayload visible(
            String userId,
            String username,
            String displayName,
            String avatarUrl
    ) {
        return new PublicUserProfilePayload(userId, username, displayName, avatarUrl, "ACTIVE");
    }
}
