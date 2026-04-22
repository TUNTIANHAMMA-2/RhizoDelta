package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 当前用户自身画像的读取与更新接口。
 *
 * <p>读：{@code GET /api/users/me/profile} 返回当前认证用户的画像；
 * 画像不存在时 {@code display_name} 回退为 {@code username}，其余字段为 {@code null}。
 *
 * <p>写：{@code PUT /api/users/me/profile} 接受一个 JSON 对象，仅在
 * 请求体中出现的可变字段（display_name / avatar_url / language / timezone /
 * theme / notification_prefs）会被覆盖写入；显式 null 意味着清空该字段；
 * 未出现的字段保持不变。任何一次成功更新会把 {@code updated_at} 提至当前时刻。
 *
 * <p>两个接口都不接受 {@code user_id} 参数，只能操作调用方自己的画像。
 */
@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {
    private static final String FIND_PROFILE_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN user.username                AS username,
                   profile.display_name         AS displayName,
                   profile.avatar_url           AS avatarUrl,
                   profile.language             AS language,
                   profile.timezone             AS timezone,
                   profile.theme                AS theme,
                   profile.notification_prefs   AS notificationPrefs,
                   toString(profile.updated_at) AS updatedAt
            """;

    private final Neo4jClient neo4jClient;

    public UserProfileController(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @GetMapping("/profile")
    public ApiResponse<UserProfilePayload> getMyProfile(Authentication authentication) {
        AuthenticatedUser user = requireAuthenticatedUser(authentication);
        Map<String, Object> record = fetchProfile(user.sub())
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        return ApiResponse.ok(toPayload(user.sub(), record));
    }

    @PutMapping("/profile")
    public ApiResponse<UserProfilePayload> updateMyProfile(
            @RequestBody(required = false) UpdateUserProfileRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser user = requireAuthenticatedUser(authentication);
        if (request == null || request.presentMutableFields().isEmpty()) {
            throw new IllegalArgumentException("empty profile update");
        }
        applyUpdate(user.sub(), request);
        Map<String, Object> record = fetchProfile(user.sub())
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        return ApiResponse.ok(toPayload(user.sub(), record));
    }

    private java.util.Optional<Map<String, Object>> fetchProfile(String userId) {
        return neo4jClient.query(FIND_PROFILE_QUERY)
                .bind(userId)
                .to("userId")
                .fetch()
                .one();
    }

    private void applyUpdate(String userId, UpdateUserProfileRequest request) {
        Set<String> presentFields = request.presentMutableFields();
        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        for (String field : UpdateUserProfileRequest.MUTABLE_FIELDS) {
            if (!presentFields.contains(field)) {
                continue;
            }
            String bindName = bindNameFor(field);
            assignments.add("profile." + field + " = $" + bindName);
            params.put(bindName, request.stringOrNull(field));
        }
        assignments.add("profile.updated_at = datetime()");
        String cypher = """
                MATCH (user:UserAccount {user_id: $userId})
                MERGE (profile:UserProfile {user_id: user.user_id})
                SET %s
                MERGE (user)-[:HAS_PROFILE]->(profile)
                RETURN profile.user_id AS userId
                """.formatted(String.join(", ", assignments));
        neo4jClient.query(cypher).bindAll(params).run();
    }

    private String bindNameFor(String field) {
        StringBuilder builder = new StringBuilder(field.length());
        boolean capitalize = false;
        for (int index = 0; index < field.length(); index++) {
            char c = field.charAt(index);
            if (c == '_') {
                capitalize = true;
                continue;
            }
            builder.append(capitalize ? Character.toUpperCase(c) : c);
            capitalize = false;
        }
        return builder.toString();
    }

    private UserProfilePayload toPayload(String userId, Map<String, Object> record) {
        Object displayNameRaw = record.get("displayName");
        String resolvedDisplayName = displayNameRaw != null && !displayNameRaw.toString().isBlank()
                ? displayNameRaw.toString()
                : record.get("username").toString();
        return new UserProfilePayload(
                userId,
                resolvedDisplayName,
                stringOrNull(record.get("avatarUrl")),
                stringOrNull(record.get("language")),
                stringOrNull(record.get("timezone")),
                stringOrNull(record.get("theme")),
                stringOrNull(record.get("notificationPrefs")),
                stringOrNull(record.get("updatedAt"))
        );
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }
}
