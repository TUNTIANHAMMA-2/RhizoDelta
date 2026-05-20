package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
import com.rhizodelta.infrastructure.user.service.OnlineStatusService;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationPolicy;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PreferenceEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class PreferenceEventControllerWebTest {
    private static final String TEST_SECRET =
            "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PreferenceEventService preferenceEventService;

    @MockBean
    private PrefersAggregationPolicy prefersAggregationPolicy;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private OnlineStatusService onlineStatusService;

    @BeforeEach
    void setUpPolicy() {
        when(prefersAggregationPolicy.baseWeight(any())).thenAnswer(invocation -> switch (invocation.getArgument(0).toString()) {
            case "VIEW" -> 0.5;
            case "EXPAND" -> 1.0;
            case "DWELL" -> 1.5;
            case "LIKE" -> 2.0;
            case "SHARE" -> 3.0;
            default -> 0.0;
        });
    }

    @Test
    void createEventShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/users/me/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"EXPAND","weight":1.0,"sourceNodeId":"node-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        verifyNoInteractions(preferenceEventService);
    }

    @Test
    void createEventShouldDelegateToPreferenceEventService() throws Exception {
        mockMvc.perform(post("/api/users/me/events")
                        .header("Authorization", "Bearer " + generateToken("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DWELL","topicId":"topic-1","sourceNodeId":"node-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(preferenceEventService).recordEvent(
                "user-123",
                "topic-1",
                "DWELL",
                1.5,
                "node-1"
        );
    }

    @Test
    void createEventShouldRejectInvalidType() throws Exception {
        mockMvc.perform(post("/api/users/me/events")
                        .header("Authorization", "Bearer " + generateToken("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"OPEN","weight":99.0,"sourceNodeId":"node-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(preferenceEventService, never()).recordEvent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void createEventShouldRejectClientWeightThatDiffersFromPolicy() throws Exception {
        mockMvc.perform(post("/api/users/me/events")
                        .header("Authorization", "Bearer " + generateToken("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DWELL","weight":9.0,"sourceNodeId":"node-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(preferenceEventService, never()).recordEvent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    private static String generateToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS))
                .signWith(key)
                .compact();
    }
}
