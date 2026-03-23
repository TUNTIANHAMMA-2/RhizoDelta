package com.rhizodelta.api;

import com.rhizodelta.config.JwtAuthenticationFilter;
import com.rhizodelta.config.SecurityConfig;
import com.rhizodelta.domain.review.ReviewTask;
import com.rhizodelta.service.ReviewTaskService;
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
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class ReviewControllerWebTest {
    private static final String TEST_SECRET =
            "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewTaskService reviewTaskService;

    @Test
    void shouldReturnPendingReviewsForAgentRole() throws Exception {
        when(reviewTaskService.findPendingTasks(10)).thenReturn(List.of(sampleTask("review-1")));

        mockMvc.perform(get("/api/reviews/pending?limit=10")
                        .header("Authorization", "Bearer " + generateTokenWithRole("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].review_id").value("review-1"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    @Test
    void shouldReturnReviewByIdForAgentRole() throws Exception {
        when(reviewTaskService.getTask("review-2")).thenReturn(sampleTask("review-2"));

        mockMvc.perform(get("/api/reviews/review-2")
                        .header("Authorization", "Bearer " + generateTokenWithRole("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_id").value("review-2"))
                .andExpect(jsonPath("$.data.suggested_action").value("REVIEW"));
    }

    @Test
    void shouldRejectUserRoleAccessToReviewEndpoints() throws Exception {
        mockMvc.perform(get("/api/reviews/pending")
                        .header("Authorization", "Bearer " + generateTokenWithRole("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    private static ReviewTask sampleTask(String reviewId) {
        return new ReviewTask(
                reviewId,
                "req-1",
                "post-1",
                "trace-1",
                ReviewTask.Status.PENDING,
                "REVIEW",
                List.of("candidate-1"),
                Map.of("summary_content", "draft"),
                List.of("LOW_CONFIDENCE"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-30T00:00:00Z")
        );
    }

    private static String generateTokenWithRole(String role) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("review-operator")
                .claim("roles", List.of(role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS))
                .signWith(key)
                .compact();
    }
}
