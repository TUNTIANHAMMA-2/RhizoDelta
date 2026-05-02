package com.rhizodelta.infrastructure.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the M3 fix: the userIndex tombstone is extended to
 * reuse-detection-ttl on successful consume(), independent of refresh-ttl.
 * Without this, a stolen token replayed AFTER the rotation window is
 * indistinguishable from a plain expired token and the cascade revoke
 * never fires.
 */
class RefreshTokenServiceReuseDetectionUnitTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SetOperations<String, String> setOps = mock(SetOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void successfulConsumeExtendsUserIndexTombstoneToReuseDetectionTtl() {
        Duration refreshTtl = Duration.ofDays(30);
        Duration reuseTtl = Duration.ofDays(90);
        RefreshTokenService service =
                new RefreshTokenService(redis, objectMapper, refreshTtl, reuseTtl);

        // Stub: payload exists (not yet consumed) -> consume() succeeds.
        when(valueOps.getAndDelete(contains("refresh:")))
                .thenReturn("{\"user_id\":\"u1\",\"issued_at\":\"x\",\"expires_at\":\"y\"}");

        String userId = service.consume("token-abc");

        assertThat(userId).isEqualTo("u1");

        // The userIndex tombstone TTL must be extended to reuseDetectionTtl,
        // NOT left at the original refresh-ttl that was set by issue().
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redis, atLeastOnce()).expire(keyCaptor.capture(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));

        boolean tombstoneExtended = false;
        for (int i = 0; i < keyCaptor.getAllValues().size(); i++) {
            String k = keyCaptor.getAllValues().get(i);
            Long ttl = ttlCaptor.getAllValues().get(i);
            if (k.startsWith("refresh:hash-user:") && ttl == reuseTtl.getSeconds()) {
                tombstoneExtended = true;
            }
        }
        assertThat(tombstoneExtended)
                .as("userIndex tombstone must be re-EXPIREd to reuse-detection-ttl, not left at refresh-ttl")
                .isTrue();
    }

    @Test
    void replayAfterTombstoneStillRecognizedAsReuse() {
        // This is the high-level invariant the fix protects: as long as the
        // tombstone is alive, replays of a consumed token must throw
        // "refresh token reused; all sessions revoked" rather than
        // "invalid or expired".
        Duration refreshTtl = Duration.ofDays(30);
        Duration reuseTtl = Duration.ofDays(90);
        RefreshTokenService service =
                new RefreshTokenService(redis, objectMapper, refreshTtl, reuseTtl);

        // First call: payload is gone (already consumed once). Tombstone still resolves to u1.
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        when(valueOps.get(contains("refresh:hash-user:"))).thenReturn("u1");
        when(setOps.members(anyString())).thenReturn(java.util.Set.of());

        assertThatThrownBy(() -> service.consume("token-abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reused");
    }
}
