package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.exception.ConflictException;
import com.rhizodelta.infrastructure.user.repository.MuteRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class MuteService {
    private static final Set<String> VALID_TARGET_TYPES = Set.of("topic", "user");

    private final MuteRepository muteRepository;

    public MuteService(MuteRepository muteRepository) {
        this.muteRepository = muteRepository;
    }

    public Map<String, Object> mute(String userId, String targetType, String targetId, String reason) {
        validateTargetType(targetType);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("target_id must not be blank");
        }
        String muteId = UUID.randomUUID().toString();
        Map<String, Object> record = muteRepository.create(userId, targetType, targetId, muteId, reason)
                .orElseThrow(() -> new NoSuchElementException("target not found: " + targetType + ":" + targetId));
        boolean alreadyExisted = Boolean.TRUE.equals(record.get("already_existed"));
        if (alreadyExisted) {
            throw new ConflictException("already muted " + targetType + ":" + targetId);
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("mute_id", record.get("mute_id"));
        response.put("target_type", targetType);
        response.put("target_id", targetId);
        response.put("since", record.get("since"));
        response.put("reason", reason == null ? "" : reason);
        response.put("status", "muted");
        return response;
    }

    public Map<String, Object> listMutes(String userId, int page, int size) {
        int skip = Math.max(page, 0) * size;
        List<Map<String, Object>> items = muteRepository.listMutes(userId, skip, size);
        long total = muteRepository.countMutes(userId);
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

    public void unmute(String userId, String muteId) {
        if (muteId == null || muteId.isBlank()) {
            throw new IllegalArgumentException("mute_id must not be blank");
        }
        boolean deleted = muteRepository.deleteById(userId, muteId);
        if (!deleted) {
            throw new NoSuchElementException("mute relationship not found: " + muteId);
        }
    }

    private static void validateTargetType(String targetType) {
        if (targetType == null || !VALID_TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("target_type must be one of: topic, user");
        }
    }
}
