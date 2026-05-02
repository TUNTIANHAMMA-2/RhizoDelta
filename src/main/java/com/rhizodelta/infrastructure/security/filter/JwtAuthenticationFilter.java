package com.rhizodelta.infrastructure.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
import com.rhizodelta.infrastructure.user.service.OnlineStatusService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretKey signingKey;
    private final ObjectMapper objectMapper;
    private final TokenBlacklistService tokenBlacklistService;
    private final OnlineStatusService onlineStatusService;

    public JwtAuthenticationFilter(
            @Value("${rhizodelta.jwt.secret}") String jwtSecret,
            ObjectMapper objectMapper,
            TokenBlacklistService tokenBlacklistService,
            OnlineStatusService onlineStatusService
    ) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
        this.tokenBlacklistService = tokenBlacklistService;
        this.onlineStatusService = onlineStatusService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String jti = claims.getId();
            if (jti != null && isJtiRevokedSafe(jti)) {
                throw new BadCredentialsException("token revoked");
            }

            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new BadCredentialsException("invalid token");
            }

            // 用户级吊销：账户被禁/删或检测到 refresh-token 盗用时，
            // 在该时刻之前签发的 access token 必须立即失效。
            java.time.Instant revokedBefore = revokedBeforeSafe(sub);
            java.util.Date issuedAt = claims.getIssuedAt();
            if (revokedBefore != null
                    && issuedAt != null
                    && issuedAt.toInstant().isBefore(revokedBefore)) {
                throw new BadCredentialsException("token revoked");
            }

            List<String> roles = extractRoles(claims);
            AuthenticatedUser user = new AuthenticatedUser(sub, roles);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.authorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            onlineStatusService.recordActivity(sub);

            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            LOGGER.debug("Expired JWT token: {}", e.getMessage());
            writeErrorResponse(response, "token expired");
        } catch (JwtException e) {
            LOGGER.debug("Invalid JWT token: {}", e.getMessage());
            writeErrorResponse(response, "invalid token");
        } catch (AuthenticationException e) {
            LOGGER.debug("Authentication failure: {}", e.getMessage());
            writeErrorResponse(response, e.getMessage());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * 黑名单查询失败时 fail-open：Redis 短暂故障不应该让所有已签发的 token 拒登。
     * 签名仍然校验通过，撤销是 defense-in-depth；故障期间放行 + 告警是更可用的取舍。
     *
     * <p>注：{@link TokenBlacklistService#isRevoked} 内部已对 Redis 异常 fail-open，
     * 这里再保留一层 try-catch 是 defense-in-depth：在测试或 Spring AOP 代理路径
     * 直接抛 {@link RuntimeException} 时（绕过 service 的 catch），filter 仍能放行；
     * 同一请求 service+filter 至多打印一条 WARN，不会重复刷屏。
     */
    private boolean isJtiRevokedSafe(String jti) {
        try {
            return tokenBlacklistService.isRevoked(jti);
        } catch (RuntimeException ex) {
            LOGGER.warn("token blacklist check failed, fail-open: {}", ex.getMessage());
            return false;
        }
    }

    private java.time.Instant revokedBeforeSafe(String sub) {
        try {
            return tokenBlacklistService.revokedBefore(sub);
        } catch (RuntimeException ex) {
            LOGGER.warn("user-level revoke check failed, fail-open: {}", ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?>) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.unauthorized(message));
    }
}
