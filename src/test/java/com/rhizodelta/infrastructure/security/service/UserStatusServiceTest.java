package com.rhizodelta.infrastructure.security.service;

import com.rhizodelta.infrastructure.security.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 13.7: Validates that user suspension triggers refresh token revocation.
 */
class UserStatusServiceTest {

    @Test
    void suspendedStatusRevokesAllRefreshTokens() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        String userId = "user-123";

        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("userId", userId, "status", "SUSPENDED")));

        UserStatusService service = new UserStatusService(neo4jClient, refreshTokenService, tokenBlacklistService);
        Map<String, Object> returned = service.changeStatus(userId, UserStatus.SUSPENDED);

        assertThat(returned).containsEntry("userId", userId);
        verify(refreshTokenService).revokeAllForUser(userId);
        verify(tokenBlacklistService).revokeAllForUser(userId);
    }

    @Test
    void deletedStatusRevokesAllRefreshTokens() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        String userId = "user-456";

        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("userId", userId, "status", "DELETED")));

        UserStatusService service = new UserStatusService(neo4jClient, refreshTokenService, tokenBlacklistService);
        service.changeStatus(userId, UserStatus.DELETED);

        verify(refreshTokenService).revokeAllForUser(userId);
        verify(tokenBlacklistService).revokeAllForUser(userId);
    }

    @Test
    void activeStatusDoesNotRevokeTokens() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        String userId = "user-789";

        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.of(Map.of("userId", userId, "status", "ACTIVE")));

        UserStatusService service = new UserStatusService(neo4jClient, refreshTokenService, tokenBlacklistService);
        service.changeStatus(userId, UserStatus.ACTIVE);

        verify(refreshTokenService, never()).revokeAllForUser(anyString());
        verify(tokenBlacklistService, never()).revokeAllForUser(anyString());
    }

    @Test
    void unknownUserThrowsException() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);

        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().one())
                .thenReturn(Optional.empty());

        UserStatusService service = new UserStatusService(neo4jClient, refreshTokenService, tokenBlacklistService);

        assertThatThrownBy(() -> service.changeStatus("nonexistent", UserStatus.SUSPENDED))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("user not found");

        verify(refreshTokenService, never()).revokeAllForUser(anyString());
    }
}
