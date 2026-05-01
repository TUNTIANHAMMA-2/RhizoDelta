package com.rhizodelta.infrastructure.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OnlineStatusService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OnlineStatusService.class);
    private static final String ONLINE_KEY_PREFIX = "user:online:";
    private static final Duration ONLINE_TTL = Duration.ofMinutes(5);
    private static final int MAX_BATCH_SIZE = 50;

    private final RedisTemplate<String, String> onlineStatusRedisTemplate;

    public OnlineStatusService(RedisTemplate<String, String> onlineStatusRedisTemplate) {
        this.onlineStatusRedisTemplate = onlineStatusRedisTemplate;
    }

    public void recordActivity(String userId) {
        try {
            onlineStatusRedisTemplate.opsForValue()
                    .set(ONLINE_KEY_PREFIX + userId, String.valueOf(System.currentTimeMillis()), ONLINE_TTL);
        } catch (Exception e) {
            LOGGER.debug("Failed to record online activity for user={}: {}", userId, e.getMessage());
        }
    }

    public Map<String, Object> getStatus(String userId) {
        try {
            String ts = onlineStatusRedisTemplate.opsForValue().get(ONLINE_KEY_PREFIX + userId);
            if (ts != null) {
                return Map.of("user_id", userId, "online", true, "last_active", ts);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to check online status for user={}: {}", userId, e.getMessage());
        }
        return Map.of("user_id", userId, "online", false);
    }

    public List<Map<String, Object>> getBatchStatus(List<String> userIds) {
        if (userIds.size() > MAX_BATCH_SIZE) {
            userIds = userIds.subList(0, MAX_BATCH_SIZE);
        }
        try {
            List<String> keys = userIds.stream().map(id -> ONLINE_KEY_PREFIX + id).toList();
            List<String> values = onlineStatusRedisTemplate.opsForValue().multiGet(keys);
            List<Map<String, Object>> result = new java.util.ArrayList<>(userIds.size());
            for (int i = 0; i < userIds.size(); i++) {
                String ts = values != null ? values.get(i) : null;
                if (ts != null) {
                    result.add(Map.of("user_id", userIds.get(i), "online", true, "last_active", ts));
                } else {
                    result.add(Map.of("user_id", userIds.get(i), "online", false));
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.debug("Failed to batch check online status: {}", e.getMessage());
            return userIds.stream()
                    .map(id -> Map.<String, Object>of("user_id", id, "online", false))
                    .toList();
        }
    }
}
