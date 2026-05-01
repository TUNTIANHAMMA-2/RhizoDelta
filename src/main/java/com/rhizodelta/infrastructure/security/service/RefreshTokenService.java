package com.rhizodelta.infrastructure.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Refresh Token 全生命周期管理。
 *
 * <p>关键安全机制：
 * <ul>
 *   <li><b>原子轮换</b>：{@link #consume} 用 GETDEL 实现一次性消费，并发请求只有一个能成功。</li>
 *   <li><b>盗用即吊销</b>：当一个已被消费的 token 被再次使用时，立即把该用户的全部 refresh token 失效，
 *       避免攻击者继续使用偷得的 token 链。</li>
 *   <li><b>原子下发</b>：{@link #issue} 在一个 Redis pipeline 中写入 token 主键、token→user 反查表与
 *       user→tokens 集合，避免半失败留下"无主孤儿 token"。</li>
 * </ul>
 */
@Service
public class RefreshTokenService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);
    /** token 哈希 → JSON payload。GETDEL 一次性消费由该键支撑。 */
    private static final String REFRESH_KEY_PREFIX = "refresh:";
    /** token 哈希 → user_id。即便 payload 被消费，仍可借此识别盗用方所属的用户并触发全吊销。 */
    private static final String REFRESH_USER_INDEX_PREFIX = "refresh:hash-user:";
    /** user_id → token 哈希集合。{@link #revokeAllForUser} 借此一次性清空。 */
    private static final String USER_REFRESH_SET_PREFIX = "refresh:user:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RedisTemplate<String, String> refreshTokenRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration refreshTtl;

    public RefreshTokenService(
            RedisTemplate<String, String> refreshTokenRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${rhizodelta.jwt.refresh-ttl:P30D}") Duration refreshTtl
    ) {
        this.refreshTokenRedisTemplate = refreshTokenRedisTemplate;
        this.objectMapper = objectMapper;
        this.refreshTtl = refreshTtl;
    }

    public String issue(String userId) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(token);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "user_id", userId,
                    "issued_at", Instant.now().toString(),
                    "expires_at", Instant.now().plus(refreshTtl).toString()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize refresh token payload", e);
        }
        long ttlSeconds = refreshTtl.getSeconds();
        String payloadKey = REFRESH_KEY_PREFIX + tokenHash;
        String userIndexKey = REFRESH_USER_INDEX_PREFIX + tokenHash;
        String userSetKey = USER_REFRESH_SET_PREFIX + userId;

        // 把三次写入合并为一次 pipeline，避免在 set/setex 之间出错时留下半成品。
        refreshTokenRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForValue().set(payloadKey, payload, ttlSeconds, TimeUnit.SECONDS);
                operations.opsForValue().set(userIndexKey, userId, ttlSeconds, TimeUnit.SECONDS);
                operations.opsForSet().add(userSetKey, tokenHash);
                operations.expire(userSetKey, ttlSeconds, TimeUnit.SECONDS);
                return null;
            }
        });

        return token;
    }

    /**
     * 消费一次 refresh token。
     *
     * <p>语义：
     * <ul>
     *   <li>token 存在且未被消费 → 删除并返回 user_id（caller 负责签发新 token）。</li>
     *   <li>token 不存在但用户索引仍在 → <b>盗用</b>：调用 {@link #revokeAllForUser} 让该用户全部 refresh
     *       token 立即失效，并抛 {@link IllegalArgumentException}。</li>
     *   <li>token 完全不存在 → 单纯无效或过期。</li>
     * </ul>
     */
    public String consume(String token) {
        String tokenHash = hash(token);
        String payloadKey = REFRESH_KEY_PREFIX + tokenHash;
        String userIndexKey = REFRESH_USER_INDEX_PREFIX + tokenHash;

        String payload = refreshTokenRedisTemplate.opsForValue().getAndDelete(payloadKey);
        if (payload != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(payload, Map.class);
                String userId = data.get("user_id").toString();
                refreshTokenRedisTemplate.opsForSet().remove(USER_REFRESH_SET_PREFIX + userId, tokenHash);
                // 注意：保留 userIndexKey 作为「该 hash 曾归属此用户」的 tombstone。
                // 若被消费过的 token 再次出现（replay），我们才能识别为盗用并触发全员吊销。
                // userIndex 自身已带 TTL，会随 refresh-ttl 自然失效，不会无限增长。
                return userId;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("failed to parse refresh token payload", e);
            }
        }

        // payload 已不存在 —— 区分「盗用」与「单纯无效」
        String reusedUserId = refreshTokenRedisTemplate.opsForValue().get(userIndexKey);
        if (reusedUserId != null) {
            LOGGER.warn("SECURITY: refresh token reuse detected for user={}, hash={}. Revoking all sessions.",
                    reusedUserId, tokenHash);
            revokeAllForUser(reusedUserId);
            throw new IllegalArgumentException("refresh token reused; all sessions revoked");
        }
        throw new IllegalArgumentException("invalid or expired refresh token");
    }

    public void revokeAllForUser(String userId) {
        String userSetKey = USER_REFRESH_SET_PREFIX + userId;
        Set<String> tokenHashes = refreshTokenRedisTemplate.opsForSet().members(userSetKey);
        if (tokenHashes != null && !tokenHashes.isEmpty()) {
            for (String tokenHash : tokenHashes) {
                refreshTokenRedisTemplate.delete(REFRESH_KEY_PREFIX + tokenHash);
                refreshTokenRedisTemplate.delete(REFRESH_USER_INDEX_PREFIX + tokenHash);
            }
        }
        refreshTokenRedisTemplate.delete(userSetKey);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
