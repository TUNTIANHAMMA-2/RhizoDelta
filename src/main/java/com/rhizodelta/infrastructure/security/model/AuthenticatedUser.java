package com.rhizodelta.infrastructure.security.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

public record AuthenticatedUser(String sub, List<String> roles) implements Principal {

    @Override
    public String getName() {
        return sub;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
