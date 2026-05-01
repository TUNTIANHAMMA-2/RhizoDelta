package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.service.AvatarStorageService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 头像上传 / 删除接口。
 *
 * <p>这两个端点不仅操作对象存储，还同步把 {@code UserProfile.avatar_url} 写回 Neo4j ——
 * 没有这一步，刷新页面后头像会消失。
 */
@RestController
@RequestMapping("/api/users/me")
public class AvatarController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvatarController.class);

    private static final String UPDATE_AVATAR_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            MERGE (profile:UserProfile {user_id: user.user_id})
            SET profile.avatar_url = $avatarUrl,
                profile.updated_at = datetime()
            MERGE (user)-[:HAS_PROFILE]->(profile)
            RETURN user.username AS username,
                   profile.display_name AS displayName,
                   profile.avatar_url AS avatarUrl,
                   profile.language AS language,
                   profile.timezone AS timezone,
                   profile.theme AS theme,
                   profile.notification_prefs AS notificationPrefs,
                   toString(profile.updated_at) AS updatedAt
            """;
    private static final String CLEAR_AVATAR_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            WITH user, profile
            FOREACH (_ IN CASE WHEN profile IS NOT NULL THEN [1] ELSE [] END |
              SET profile.avatar_url = null,
                  profile.updated_at = datetime()
            )
            """;

    private final AvatarStorageService avatarStorageService;
    private final Neo4jClient neo4jClient;

    public AvatarController(AvatarStorageService avatarStorageService, Neo4jClient neo4jClient) {
        this.avatarStorageService = avatarStorageService;
        this.neo4jClient = neo4jClient;
    }

    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        var user = AuthenticatedUsers.require(authentication);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        byte[] content = file.getBytes();
        avatarStorageService.validateFile(file.getContentType(), file.getSize(), content);
        String objectPath = avatarStorageService.upload(user.sub(), content, file.getContentType());
        Map<String, Object> profile = persistAvatarUrl(user.sub(), objectPath);
        return ApiResponse.ok(profile);
    }

    @DeleteMapping("/avatar")
    public ApiResponse<Void> deleteAvatar(Authentication authentication) throws IOException {
        var user = AuthenticatedUsers.require(authentication);
        try {
            avatarStorageService.delete(user.sub());
        } catch (IOException e) {
            LOGGER.warn("Failed to delete avatar object for user {}: {}", user.sub(), e.getMessage());
        }
        clearAvatarUrl(user.sub());
        return ApiResponse.ok(null);
    }

    private Map<String, Object> persistAvatarUrl(String userId, String objectPath) {
        Map<String, Object> record = neo4jClient.query(UPDATE_AVATAR_QUERY)
                .bindAll(Map.of("userId", userId, "avatarUrl", objectPath))
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("user not found: " + userId));
        return toProfilePayload(userId, record);
    }

    private void clearAvatarUrl(String userId) {
        neo4jClient.query(CLEAR_AVATAR_QUERY)
                .bind(userId).to("userId")
                .run();
    }

    private Map<String, Object> toProfilePayload(String userId, Map<String, Object> record) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        Object usernameRaw = record.get("username");
        Object displayNameRaw = record.get("displayName");
        String username = usernameRaw != null ? usernameRaw.toString() : null;
        String displayName = displayNameRaw == null || displayNameRaw.toString().isBlank()
                ? username
                : displayNameRaw.toString();
        payload.put("display_name", displayName);
        String storedAvatar = stringOrNull(record.get("avatarUrl"));
        payload.put("avatar_url", storedAvatar == null ? null : avatarStorageService.getPresignedUrl(storedAvatar));
        payload.put("language", stringOrNull(record.get("language")));
        payload.put("timezone", stringOrNull(record.get("timezone")));
        payload.put("theme", stringOrNull(record.get("theme")));
        payload.put("notification_prefs", stringOrNull(record.get("notificationPrefs")));
        payload.put("updated_at", stringOrNull(record.get("updatedAt")));
        return payload;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
