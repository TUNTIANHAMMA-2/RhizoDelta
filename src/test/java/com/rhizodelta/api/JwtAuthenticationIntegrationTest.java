package com.rhizodelta.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NodeQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class JwtAuthenticationIntegrationTest {

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
    private OnlineStatusService onlineStatusService;

    @Test
    void requestWithoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("authentication required"));
    }

    @Test
    void requestWithValidToken_returns200() throws Exception {
        when(nodeQueryService.getNodeSummaryById(TEST_NODE_ID))
                .thenReturn(new NodeQueryService.LineageNode(
                        TEST_NODE_ID.toString(), "Human_Post", "test content", null,
                        "author-1", null, Instant.now(), false
                ));

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateValidToken("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_id").value(TEST_NODE_ID.toString()));
    }

    @Test
    void requestWithExpiredToken_returns401() throws Exception {
        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateExpiredToken("user-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("token expired"));
    }

    @Test
    void userRoleCanAccessGetNodes() throws Exception {
        when(nodeQueryService.getNodeSummaryById(TEST_NODE_ID))
                .thenReturn(new NodeQueryService.LineageNode(
                        TEST_NODE_ID.toString(), "Human_Post", "test content", null,
                        "author-1", null, Instant.now(), false
                ));

        mockMvc.perform(get("/api/nodes/" + TEST_NODE_ID)
                        .header("Authorization", "Bearer " + generateValidToken("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_id").value(TEST_NODE_ID.toString()));
    }

    @Test
    void userRoleCannotPostDecisionsMerge() throws Exception {
        mockMvc.perform(post("/api/decisions/merge")
                        .header("Authorization", "Bearer " + generateValidToken("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision_id\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("insufficient permissions"));
    }

    private String generateValidToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private String generateExpiredToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key)
                .compact();
    }
}
