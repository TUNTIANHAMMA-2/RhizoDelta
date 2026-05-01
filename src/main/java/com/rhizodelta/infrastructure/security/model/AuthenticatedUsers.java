package com.rhizodelta.infrastructure.security.model;

import org.springframework.security.core.Authentication;

/**
 * 认证用户提取工具。
 *
 * <p>各控制器用相同的逻辑从 {@link Authentication} 中提取
 * {@link AuthenticatedUser}；本类把这段逻辑集中到一处，避免
 * 每个控制器都重复实现同一个 {@code requireUser} 方法。
 */
public final class AuthenticatedUsers {

    private AuthenticatedUsers() {}

    /**
     * 从认证上下文中提取当前用户主体。
     *
     * @throws IllegalStateException 若认证上下文为空或主体类型不匹配
     */
    public static AuthenticatedUser require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }
}
