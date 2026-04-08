package com.rhizodelta.infrastructure.security.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * 表示当前已认证用户。
 *
 * <p>该对象会被写入 Spring Security 上下文，供控制器和服务层读取用户标识与角色集合。
 */
public record AuthenticatedUser(String sub, List<String> roles) implements Principal {

    @Override
    public String getName() {
        return sub;
    }

    /**
     * 将角色列表转换为 Spring Security 权限对象。
     *
     * <p>空角色列表会返回空权限集合，而不是抛异常。
     */
    public Collection<? extends GrantedAuthority> authorities() {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
