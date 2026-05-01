package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.exception.ConflictException;
import com.rhizodelta.infrastructure.user.repository.FollowRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class FollowService {
    private static final Set<String> VALID_TARGET_TYPES = Set.of("topic", "node", "user");

    private final FollowRepository followRepository;

    public FollowService(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    public Map<String, Object> follow(String userId, String targetType, String targetId) {
        validateTargetType(targetType);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("target_id must not be blank");
        }
        String followId = UUID.randomUUID().toString();
        Map<String, Object> record = followRepository.create(userId, targetType, targetId, followId)
                .orElseThrow(() -> new NoSuchElementException("target not found: " + targetType + ":" + targetId));
        boolean alreadyExisted = Boolean.TRUE.equals(record.get("already_existed"));
        if (alreadyExisted) {
            throw new ConflictException("already following " + targetType + ":" + targetId);
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("follow_id", record.get("follow_id"));
        response.put("target_type", targetType);
        response.put("target_id", targetId);
        response.put("since", record.get("since"));
        response.put("status", "following");
        return response;
    }

    public Map<String, Object> listFollows(String userId, int page, int size) {
        int skip = Math.max(page, 0) * size;
        List<Map<String, Object>> items = followRepository.listFollows(userId, skip, size);
        long total = followRepository.countFollows(userId);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        return Map.of(
                "items", items,
                "page", page,
                "size", size,
                "total", total,
                "total_pages", totalPages,
                "has_next", page + 1 < totalPages
        );
    }

    public void unfollow(String userId, String followId) {
        if (followId == null || followId.isBlank()) {
            throw new IllegalArgumentException("follow_id must not be blank");
        }
        boolean deleted = followRepository.deleteById(userId, followId);
        if (!deleted) {
            throw new NoSuchElementException("follow relationship not found: " + followId);
        }
    }

    private static void validateTargetType(String targetType) {
        if (targetType == null || !VALID_TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("target_type must be one of: topic, node, user");
        }
    }
}
