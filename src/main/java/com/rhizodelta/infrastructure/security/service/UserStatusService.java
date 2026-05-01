package com.rhizodelta.infrastructure.security.service;

import com.rhizodelta.infrastructure.security.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 管理用户账户生命周期状态变更。
 *
 * <p>当用户状态从 ACTIVE 变为 SUSPENDED 或 DELETED 时，
 * 同步撤销所有 Refresh Token 和 JWT，确保已发出的凭据立即失效。
 *
 * <p>本服务仅负责状态写入 + 凭据联动撤销，不包含触发逻辑
 * （触发点由管理员接口或定时任务决定）。
 */
@Service
public class UserStatusService {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserStatusService.class);

    private static final String UPDATE_STATUS_QUERY = """
            MATCH (u:UserAccount {user_id: $userId})
            SET u.status = $status, u.status_changed_at = datetime()
            RETURN u.user_id AS userId, u.status AS status
            """;

    private final Neo4jClient neo4jClient;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    public UserStatusService(Neo4jClient neo4jClient,
                             RefreshTokenService refreshTokenService,
                             TokenBlacklistService tokenBlacklistService) {
        this.neo4jClient = neo4jClient;
        this.refreshTokenService = refreshTokenService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 变更用户状态。若目标状态不是 ACTIVE，则同步撤销所有 Refresh Token。
     *
     * @param userId 目标用户 ID
     * @param newStatus 新状态
     * @return 更新后的状态确认
     */
    @Transactional(transactionManager = "transactionManager")
    public Map<String, Object> changeStatus(String userId, UserStatus newStatus) {
        Map<String, Object> result = neo4jClient.query(UPDATE_STATUS_QUERY)
                .bindAll(Map.of("userId", userId, "status", newStatus.name()))
                .fetch()
                .one()
                .orElseThrow(() -> new java.util.NoSuchElementException("user not found: " + userId));

        if (newStatus != UserStatus.ACTIVE) {
            LOGGER.info("User status changed to {}. Revoking all sessions for user={}.", newStatus, userId);
            refreshTokenService.revokeAllForUser(userId);
            tokenBlacklistService.revokeAllForUser(userId);
        }

        return result;
    }
}
