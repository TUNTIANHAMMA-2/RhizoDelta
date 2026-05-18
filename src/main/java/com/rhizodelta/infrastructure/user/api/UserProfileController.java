package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.user.service.AvatarStorageService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.domain.UserStatus;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

@RestController
@RequestMapping("/api/users")
public class UserProfileController {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileController.class);
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
    private static final String FIND_PUBLIC_PROFILE_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN user.user_id         AS userId,
                   user.username        AS username,
                   user.status          AS status,
                   profile.display_name AS displayName,
                   profile.avatar_url   AS avatarUrl
            """;

    private final Neo4jClient neo4jClient;
    private final AvatarStorageService avatarStorageService;

    public UserProfileController(Neo4jClient neo4jClient, AvatarStorageService avatarStorageService) {
        this.neo4jClient = neo4jClient;
        this.avatarStorageService = avatarStorageService;
    }

    @GetMapping("/me/profile")
    public ApiResponse<UserProfilePayload> getMyProfile(Authentication authentication) {
        AuthenticatedUser user = requireAuthenticatedUser(authentication);
        Map<String, Object> record = fetchProfile(user.sub())
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        return ApiResponse.ok(toPayload(user.sub(), record));
    }

    @GetMapping("/{userId}/profile")
    public ApiResponse<PublicUserProfilePayload> getPublicProfile(
            @PathVariable String userId,
            Authentication authentication
    ) {
        requireAuthenticatedUser(authentication);
        Map<String, Object> record = fetchPublicProfile(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        return ApiResponse.ok(toPublicPayload(record));
    }

    @GetMapping("/{userId}/avatar")
    public ResponseEntity<?> getAvatar(@PathVariable String userId, Authentication authentication) {
        requireAuthenticatedUser(authentication);
        Map<String, Object> record = fetchPublicProfile(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        if (!isPubliclyVisible(record.get("status"))) {
            // SUSPENDED / DELETED 账号下，与 toPublicPayload 一致 fail-close，
            // 不允许通过头像端点泄露用户存在性或生成预签名 URL。
            return ResponseEntity.notFound().build();
        }
        String avatarUrl = stringOrNull(record.get("avatarUrl"));
        if (avatarUrl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            String presignedUrl = avatarStorageService.getPresignedUrl(avatarUrl);
            if (presignedUrl != null) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, presignedUrl)
                        .build();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to generate presigned URL for user {}: {}", userId, e.getMessage());
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/me/profile")
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

    private java.util.Optional<Map<String, Object>> fetchPublicProfile(String userId) {
        return neo4jClient.query(FIND_PUBLIC_PROFILE_QUERY)
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
        String avatarUrl = stringOrNull(record.get("avatarUrl"));
        if (avatarUrl != null) {
            try {
                avatarUrl = avatarStorageService.getPresignedUrl(avatarUrl);
            } catch (Exception e) {
                LOGGER.debug("Failed to resolve avatar URL: {}", e.getMessage());
            }
        }
        return new UserProfilePayload(
                userId,
                resolvedDisplayName,
                avatarUrl,
                stringOrNull(record.get("language")),
                stringOrNull(record.get("timezone")),
                stringOrNull(record.get("theme")),
                stringOrNull(record.get("notificationPrefs")),
                stringOrNull(record.get("updatedAt"))
        );
    }

    private PublicUserProfilePayload toPublicPayload(Map<String, Object> record) {
        String userId = record.get("userId").toString();
        if (!isPubliclyVisible(record.get("status"))) {
            return PublicUserProfilePayload.unavailable(userId);
        }
        Object usernameRaw = record.get("username");
        String username = usernameRaw != null ? usernameRaw.toString() : userId;
        String displayName = resolveDisplayName(record.get("displayName"), username);
        String avatarUrl = stringOrNull(record.get("avatarUrl"));
        if (avatarUrl != null) {
            try {
                avatarUrl = avatarStorageService.getPresignedUrl(avatarUrl);
            } catch (Exception e) {
                LOGGER.debug("Failed to resolve avatar URL: {}", e.getMessage());
            }
        }
        return PublicUserProfilePayload.visible(userId, username, displayName, avatarUrl);
    }

    private static boolean isPubliclyVisible(Object statusRaw) {
        if (statusRaw == null) {
            return true;
        }
        String status = statusRaw.toString();
        return !UserStatus.SUSPENDED.name().equals(status)
                && !UserStatus.DELETED.name().equals(status);
    }

    private String resolveDisplayName(Object displayNameRaw, String username) {
        if (displayNameRaw == null) {
            return username;
        }
        String text = displayNameRaw.toString();
        return text.isBlank() ? username : text;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        return AuthenticatedUsers.require(authentication);
    }
}
