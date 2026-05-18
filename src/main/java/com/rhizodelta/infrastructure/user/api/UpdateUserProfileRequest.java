package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PUT /api/users/me/profile 的请求体包装类。
 *
 * <p>Jackson 对 record 或 POJO 无法区分"字段缺失"与"字段显式为 null"；
 * 本类接收原始 Map，并提供 {@link #isPresent(String)} / {@link #stringOrNull(String)}
 * 让 controller 可以按拆分设计语义处理：
 * <ul>
 *   <li>字段缺失：不改变存储值</li>
 *   <li>字段显式为 null：清空存储值</li>
 *   <li>字段有非 null 值：写入归一化后的新值</li>
 * </ul>
 *
 * <p>每个 mutable 字段在构造期做严格类型 / 长度 / 格式校验，校验失败抛
 * {@link IllegalArgumentException}（由全局异常处理器映射为 HTTP 400）。
 * 非字符串值不会被 {@link Object#toString()} 静默写库。
 *
 * <p>未知字段被 {@link JsonIgnoreProperties#ignoreUnknown()} 静默丢弃；
 * 显式 read-only 字段（如 {@code avatar_url}）出现在请求体中会立刻被拒绝。
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DISPLAY_NAME_MAX = 100;
    private static final int LANGUAGE_MAX = 32;
    private static final int TIMEZONE_MAX = 64;
    private static final int NOTIFICATION_PREFS_MAX = 4096;
    private static final Set<String> ALLOWED_THEMES = Set.of("light", "dark", "auto", "system");
    private static final Pattern LANGUAGE_PATTERN =
            Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    private final Set<String> presentFields;
    private final Map<String, String> normalized;

    @JsonCreator
    public UpdateUserProfileRequest(Map<String, Object> raw) {
        Map<String, Object> body = raw == null ? Map.of() : raw;
        for (String forbidden : READ_ONLY_FIELDS) {
            if (body.containsKey(forbidden)) {
                throw new IllegalArgumentException(
                        forbidden + " is read-only here; use the dedicated endpoint to update it");
            }
        }
        Set<String> present = new LinkedHashSet<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : MUTABLE_FIELDS) {
            if (!body.containsKey(field)) {
                continue;
            }
            present.add(field);
            values.put(field, normalize(field, body.get(field)));
        }
        this.presentFields = Collections.unmodifiableSet(present);
        this.normalized = Collections.unmodifiableMap(values);
    }

    /**
     * 请求体中是否出现过某个可变字段的 key（不考虑值）。
     */
    public boolean isPresent(String field) {
        return presentFields.contains(field);
    }

    /**
     * 读取字段的归一化值。
     * <ul>
     *   <li>字段未出现：返回 null（调用方应先用 {@link #isPresent(String)} 区分）</li>
     *   <li>字段显式为 null：返回 null（语义为"清空"）</li>
     *   <li>字段有合法值：返回经过 trim / 大小写归一化 / JSON 序列化后的字符串</li>
     * </ul>
     */
    public String stringOrNull(String field) {
        return normalized.get(field);
    }

    /**
     * 返回所有出现过的可变字段名集合，用于判定"请求是否涉及任何 mutable field"。
     */
    public Set<String> presentMutableFields() {
        return presentFields;
    }

    private static String normalize(String field, Object value) {
        if (value == null) {
            return null;
        }
        return switch (field) {
            case "display_name" -> normalizeDisplayName(value);
            case "language" -> normalizeLanguage(value);
            case "timezone" -> normalizeTimezone(value);
            case "theme" -> normalizeTheme(value);
            case "notification_prefs" -> normalizeNotificationPrefs(value);
            default -> throw new IllegalArgumentException("unknown mutable field: " + field);
        };
    }

    private static String normalizeDisplayName(Object value) {
        String text = requireString(value, "display_name");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("display_name must not be blank");
        }
        if (trimmed.length() > DISPLAY_NAME_MAX) {
            throw new IllegalArgumentException(
                    "display_name length exceeds " + DISPLAY_NAME_MAX + " characters");
        }
        return trimmed;
    }

    private static String normalizeLanguage(Object value) {
        String text = requireString(value, "language");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (trimmed.length() > LANGUAGE_MAX) {
            throw new IllegalArgumentException("language length exceeds " + LANGUAGE_MAX + " characters");
        }
        if (!LANGUAGE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "language must be a BCP-47 tag such as 'en' or 'en-US'");
        }
        return trimmed;
    }

    private static String normalizeTimezone(Object value) {
        String text = requireString(value, "timezone");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("timezone must not be blank");
        }
        if (trimmed.length() > TIMEZONE_MAX) {
            throw new IllegalArgumentException("timezone length exceeds " + TIMEZONE_MAX + " characters");
        }
        try {
            ZoneId.of(trimmed);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("timezone is not a valid IANA zone id: " + trimmed);
        }
        return trimmed;
    }

    private static String normalizeTheme(Object value) {
        String text = requireString(value, "theme");
        String normalized = text.trim().toLowerCase();
        if (!ALLOWED_THEMES.contains(normalized)) {
            throw new IllegalArgumentException("theme must be one of " + ALLOWED_THEMES);
        }
        return normalized;
    }

    private static String normalizeNotificationPrefs(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("notification_prefs must be a JSON object");
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("notification_prefs is not serializable", e);
        }
        if (json.length() > NOTIFICATION_PREFS_MAX) {
            throw new IllegalArgumentException(
                    "notification_prefs serialized size exceeds " + NOTIFICATION_PREFS_MAX + " bytes");
        }
        return json;
    }

    private static String requireString(Object value, String fieldName) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return s;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UpdateUserProfileRequest that
                && Objects.equals(presentFields, that.presentFields)
                && Objects.equals(normalized, that.normalized);
    }

    @Override
    public int hashCode() {
        return Objects.hash(presentFields, normalized);
    }

    @Override
    public String toString() {
        return "UpdateUserProfileRequest" + normalized;
    }
}
