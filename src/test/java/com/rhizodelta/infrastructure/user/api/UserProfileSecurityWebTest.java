package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
import com.rhizodelta.infrastructure.user.service.AvatarStorageService;
import com.rhizodelta.infrastructure.user.service.OnlineStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-layer verification for the self-profile endpoints. Confirms that
 * {@code GET}/{@code PUT /api/users/me/profile} are rejected with 401 when no JWT is
 * present. Uses MockMvc so the assertion is not perturbed by the servlet container's
 * ErrorPageFilter rewriting 401 to a 404 {@code /error} dispatch.
 */
@WebMvcTest(controllers = UserProfileController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties =
        "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class UserProfileSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private Neo4jClient neo4jClient;

    @MockBean
    private AvatarStorageService avatarStorageService;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private OnlineStatusService onlineStatusService;

    @Test
    void getProfileWithoutJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("authentication required"));
    }

    @Test
    void putProfileWithoutJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"ghost\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("authentication required"));
    }
}
