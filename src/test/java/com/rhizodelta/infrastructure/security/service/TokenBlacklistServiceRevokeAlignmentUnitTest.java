package com.rhizodelta.infrastructure.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks m2: revokeAllForUser must align to the next-second boundary so the
 * second-precision {@code iat} field can never appear "after" a revoke that
 * actually fired within the same second.
 */
class TokenBlacklistServiceRevokeAlignmentUnitTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void revokedAtIsAlignedToNextSecondBoundary() {
        TokenBlacklistService svc = new TokenBlacklistService(redis);

        Instant before = Instant.now();
        svc.revokeAllForUser("u-1");
        Instant after = Instant.now();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), valueCaptor.capture(), any(Duration.class));

        long stored = Long.parseLong(valueCaptor.getValue());
        // 必须落在 [前一秒+1000, 后一秒+1000] 这个秒边界上
        assertThat(stored % 1000)
                .as("revokedBefore must be aligned to the start of a second")
                .isZero();

        // 必须严格大于「调用前的当前秒」，否则同秒签发的 token 会被错误放行
        long beforeSecondBoundary = before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toEpochMilli();
        assertThat(stored)
                .as("revokedBefore must be strictly after the start-of-second when revoke was called")
                .isGreaterThan(beforeSecondBoundary);

        // 上限：不会跳得太远（最多 +2s 容差，避免误用）
        assertThat(stored)
                .isLessThanOrEqualTo(after.toEpochMilli() + 2000);
    }
}
