package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PUT /api/users/me/profile 的请求体包装类。
 *
 * <p>Jackson 对 record 或 POJO 无法区分"字段缺失"与"字段显式为 null"；
 * 本类接收原始 Map，并提供 {@link #isPresent(String)} / {@link #stringOrNull(String)}
 * 让 controller 可以按 D6 语义处理：
 * <ul>
 *   <li>字段缺失：不改变存储值</li>
 *   <li>字段显式为 null：清空存储值</li>
 *   <li>字段有非 null 值：写入新值</li>
 * </ul>
 *
 * <p>未知字段会被 {@link JsonIgnoreProperties#ignoreUnknown()} 静默丢弃，
 * 这与 {@code RegisterRequest} 的做法一致（参考 user-identity-hardening D2）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpdateUserProfileRequest {
    static final Set<String> MUTABLE_FIELDS = Set.of(
            "display_name",
            "language",
            "timezone",
            "theme",
            "notification_prefs"
    );

    /**
     * 在 PUT /me/profile 上显式拒绝的字段。这些字段必须走专用端点写入：
     * <ul>
     *   <li>{@code avatar_url} —— 必须经过 {@code PUT /api/users/me/avatar}
     *       的 magic-byte 校验，否则用户可写入任意外链导致 SSRF / phishing。</li>
     * </ul>
     */
    static final Set<String> READ_ONLY_FIELDS = Set.of("avatar_url");

    private final Map<String, Object> raw;

    @JsonCreator
    public UpdateUserProfileRequest(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : new LinkedHashMap<>(raw);
        for (String forbidden : READ_ONLY_FIELDS) {
            if (this.raw.containsKey(forbidden)) {
                throw new IllegalArgumentException(
                        forbidden + " is read-only here; use the dedicated endpoint to update it");
            }
        }
    }

    /**
     * 请求体中是否出现过某个可变字段的 key（不考虑值）。
     */
    public boolean isPresent(String field) {
        return raw.containsKey(field);
    }

    /**
     * 读取字段当前值；字段未出现时返回 null；字段显式为 null 时也返回 null。
     * 调用方须先用 {@link #isPresent(String)} 判断是否出现，再决定如何处理。
     */
    public String stringOrNull(String field) {
        Object value = raw.get(field);
        return value == null ? null : value.toString();
    }

    /**
     * 返回所有出现过的可变字段名集合，用于判定"请求是否涉及任何 mutable field"。
     */
    public Set<String> presentMutableFields() {
        Set<String> present = new HashSet<>();
        for (String field : MUTABLE_FIELDS) {
            if (raw.containsKey(field)) {
                present.add(field);
            }
        }
        return present;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UpdateUserProfileRequest that && Objects.equals(raw, that.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }

    @Override
    public String toString() {
        return "UpdateUserProfileRequest" + raw;
    }
}
