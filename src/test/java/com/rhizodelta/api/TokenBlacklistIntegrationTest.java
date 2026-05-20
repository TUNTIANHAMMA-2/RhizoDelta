package com.rhizodelta.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
import com.rhizodelta.infrastructure.security.service.RefreshTokenService;
import com.rhizodelta.infrastructure.user.service.OnlineStatusService;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.core.service.AssociationService;
import com.rhizodelta.query.api.NodeQueryController;
import com.rhizodelta.query.service.NodeQueryService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NodeQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class TokenBlacklistIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";
    private static final UUID TEST_NODE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NodeQueryService nodeQueryService;

    @MockBean
    private AssociationService associationService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private PreferenceEventService preferenceEventService;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private RefreshTokenService refreshTokenService;

    @MockBean
    private OnlineStatusService onlineStatusService;

    // ── 13.4: Token blacklist check ──

    @Test
    void shouldAllowRequestWhenTokenNotRevoked() throws Exception {
        when(nodeQueryService.getNodeById(eq(TEST_NODE_ID), anyString()))
                .thenReturn(new NodeQueryService.LineageNode(
                        TEST_NODE_ID.toString(), "Human_Post", "content", null,
                        "author-1", null, Instant.now(), false));
        when(tokenBlacklistService.isRevoked(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateToken("user-1", "jti-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_id").value(TEST_NODE_ID.toString()));
    }

    @Test
    void shouldRejectRequestWhenTokenIsRevoked() throws Exception {
        when(tokenBlacklistService.isRevoked(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateToken("user-1", "jti-revoked")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("token revoked"));
    }

    @Test
    void shouldFailOpenWhenBlacklistServiceThrows() throws Exception {
        when(nodeQueryService.getNodeById(eq(TEST_NODE_ID), anyString()))
                .thenReturn(new NodeQueryService.LineageNode(
                        TEST_NODE_ID.toString(), "Human_Post", "content", null,
                        "author-1", null, Instant.now(), false));
        when(tokenBlacklistService.isRevoked(anyString()))
                .thenThrow(new RuntimeException("redis connection lost"));

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateToken("user-1", "jti-flaky")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_id").value(TEST_NODE_ID.toString()));
    }

    @Test
    void shouldRejectWhenIssuedBeforeUserLevelRevoke() throws Exception {
        // Token issued at T-60s; user-level revoke fires at NOW. Filter must reject.
        when(tokenBlacklistService.isRevoked(anyString())).thenReturn(false);
        when(tokenBlacklistService.revokedBefore("user-1"))
                .thenReturn(Instant.now());

        String oldToken = generateTokenIssuedAt(
                "user-1", "jti-old",
                Instant.now().minusSeconds(60));

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("token revoked"));
    }

    @Test
    void shouldAllowWhenIssuedAfterUserLevelRevoke() throws Exception {
        when(nodeQueryService.getNodeById(eq(TEST_NODE_ID), anyString()))
                .thenReturn(new NodeQueryService.LineageNode(
                        TEST_NODE_ID.toString(), "Human_Post", "content", null,
                        "author-1", null, Instant.now(), false));
        when(tokenBlacklistService.isRevoked(anyString())).thenReturn(false);
        when(tokenBlacklistService.revokedBefore("user-1"))
                .thenReturn(Instant.now().minusSeconds(120));

        String freshToken = generateTokenIssuedAt(
                "user-1", "jti-fresh",
                Instant.now());

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
    }

    // ── helpers ──

    private String generateToken(String subject, String jti) {
        return generateTokenIssuedAt(subject, jti, Instant.now());
    }

    private String generateTokenIssuedAt(String subject, String jti, Instant issuedAt) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(jti)
                .subject(subject)
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(issuedAt))
                .expiration(new Date(issuedAt.toEpochMilli() + 3_600_000))
                .signWith(key)
                .compact();
    }
}
