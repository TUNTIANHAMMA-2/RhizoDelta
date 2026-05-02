package com.rhizodelta.infrastructure.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * JWT 黑名单服务。
 *
 * <p>支持两种粒度的吊销：
 * <ul>
 *   <li><b>jti 级</b>：单条 token 失效（典型场景：单次 logout）。</li>
 *   <li><b>用户级</b>：在某一时刻之前签发的全部 token 失效（典型场景：账户被禁/删，
 *       或检测到 refresh token 盗用之后强制下线）。</li>
 * </ul>
 *
 * <p>读路径 {@link #isRevoked} 与 {@link #revokedBefore} 均 fail-open：
 * Redis 故障返回 {@code false}/{@code null}，不阻断认证（被吊销的 token 在
 * 故障窗口内仍有效，但不会发生「Redis 宕机 = 全站下线」）。
 */
@Service
public class TokenBlacklistService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String JTI_BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private static final String USER_REVOKE_KEY_PREFIX = "jwt:user-revoke:";
    /** 用户级吊销标记的保留窗口。等同于一个 access token 的最长 TTL —— 之前的 token 已自然过期，
     *  标记可以安全清理。当前 access token 默认 8 小时，留 24h 余量。 */
    private static final Duration USER_REVOKE_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> tokenBlacklistRedisTemplate;

    public TokenBlacklistService(RedisTemplate<String, String> tokenBlacklistRedisTemplate) {
        this.tokenBlacklistRedisTemplate = tokenBlacklistRedisTemplate;
    }

    public void revoke(String jti, Duration remainingTtl) {
        tokenBlacklistRedisTemplate.opsForValue().set(JTI_BLACKLIST_KEY_PREFIX + jti, "revoked", remainingTtl);
    }

    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(tokenBlacklistRedisTemplate.hasKey(JTI_BLACKLIST_KEY_PREFIX + jti));
        } catch (Exception e) {
            LOGGER.warn("Token blacklist check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 把当前时刻设为 {@code userId} 的「revoke-before」标记。
     * 在此之前签发的所有 access token 在 {@link #revokedBefore} 比对时会被拒绝。
     *
     * <p>JWT 的 {@code iat} 字段按 RFC 7519 是秒精度，jjwt 解析回来后毫秒位是 0。
     * 我们把 revokedBefore 向上对齐到下一秒边界，避免「revoke 与 token 落在同一秒」
     * 时因毫秒位丢失出现的边界判定不一致 —— 同一秒内的 token 必定 iat ≤ revokedBefore，
     * 都被吊销，对调用方语义更直观。
     */
    public void revokeAllForUser(String userId) {
        Instant alignedToNextSecond = Instant.now().plusMillis(1000).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        tokenBlacklistRedisTemplate.opsForValue()
                .set(USER_REVOKE_KEY_PREFIX + userId,
                        String.valueOf(alignedToNextSecond.toEpochMilli()),
                        USER_REVOKE_TTL);
    }

    /**
     * 返回 {@code userId} 的「revoke-before」时间戳；不存在或读取失败返回 {@code null}。
     * caller 应把所有 {@code iat < revokedBefore} 的 token 视为无效。
     */
    public Instant revokedBefore(String userId) {
        try {
            String value = tokenBlacklistRedisTemplate.opsForValue().get(USER_REVOKE_KEY_PREFIX + userId);
            if (value == null) {
                return null;
            }
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (Exception e) {
            LOGGER.warn("User-level revoke lookup failed (fail-open): {}", e.getMessage());
            return null;
        }
    }
}
