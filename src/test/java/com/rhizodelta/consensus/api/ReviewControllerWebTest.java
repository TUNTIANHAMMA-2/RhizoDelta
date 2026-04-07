package com.rhizodelta.consensus.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.review.ReviewTask;
import com.rhizodelta.consensus.service.DecisionService;
import com.rhizodelta.consensus.service.ReviewTaskService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
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

    @MockBean
    private DecisionService decisionService;

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
    void shouldAcceptApproveMergeForAdminRole() throws Exception {
        when(reviewTaskService.getTask("review-merge")).thenReturn(sampleMergeTask("review-merge"));
        when(reviewTaskService.updateStatus("review-merge", ReviewTask.Status.APPROVED))
                .thenReturn(sampleApprovedTask("review-merge"));
        when(decisionService.executeMerge(any()))
                .thenReturn(new DecisionResult("dec-merge-1", java.util.UUID.randomUUID(), "QUEUED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reviews/review-merge/approve-merge")
                        .header("Authorization", "Bearer " + generateTokenWithRole("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.decision_id").value("dec-merge-1"));
    }

    @Test
    void shouldUseJwtSubjectAsOperatorIdWhenApprovingMerge() throws Exception {
        when(reviewTaskService.getTask("review-merge")).thenReturn(sampleMergeTask("review-merge"));
        when(reviewTaskService.updateStatus("review-merge", ReviewTask.Status.APPROVED))
                .thenReturn(sampleApprovedTask("review-merge"));
        when(decisionService.executeMerge(any()))
                .thenReturn(new DecisionResult("dec-merge-1", java.util.UUID.randomUUID(), "QUEUED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reviews/review-merge/approve-merge")
                        .header("Authorization", "Bearer " + generateToken("review-operator-123", "ADMIN")))
                .andExpect(status().isAccepted());

        verify(decisionService).executeMerge(argThat(command ->
                "review-operator-123".equals(command.operator_id())));
    }

    @Test
    void shouldDeriveBranchRequestIdWhenApprovingBranch() throws Exception {
        when(reviewTaskService.getTask("review-branch")).thenReturn(sampleBranchTask("review-branch"));
        when(reviewTaskService.updateStatus("review-branch", ReviewTask.Status.APPROVED))
                .thenReturn(sampleApprovedTask("review-branch"));
        when(decisionService.executeBranch(any()))
                .thenReturn(new DecisionResult("dec-branch-1", java.util.UUID.randomUUID(), "QUEUED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reviews/review-branch/approve-branch")
                        .header("Authorization", "Bearer " + generateToken("review-operator-branch", "ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.decision_id").value("dec-branch-1"));

        verify(decisionService).executeBranch(argThat(command ->
                "req-branch:branch".equals(command.request_id())
                        && "review-operator-branch".equals(command.operator_id())));
    }

    @Test
    void shouldAcceptRejectForAdminRole() throws Exception {
        when(reviewTaskService.updateStatus("review-reject", ReviewTask.Status.REJECTED))
                .thenReturn(sampleRejectedTask("review-reject"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reviews/review-reject/reject")
                        .header("Authorization", "Bearer " + generateTokenWithRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_id").value("review-reject"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void shouldRejectUserRoleAccessToReviewWriteEndpoints() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reviews/review-merge/approve-merge")
                        .header("Authorization", "Bearer " + generateTokenWithRole("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void shouldRejectUserRoleAccessToReviewQueryEndpoints() throws Exception {
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
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-30T00:00:00Z")
        );
    }

    private static ReviewTask sampleMergeTask(String reviewId) {
        return new ReviewTask(
                reviewId,
                "req-merge",
                "post-merge",
                "trace-merge",
                ReviewTask.Status.PENDING,
                "MERGE",
                List.of("candidate-merge"),
                Map.of(
                        "decision_id", "dec-merge-1",
                        "request_id", "req-merge",
                        "source_node_id", "11111111-1111-1111-1111-111111111111",
                        "agent_version", "agent-v1",
                        "summary_content", "merged summary",
                        "synthesized_from", List.of("22222222-2222-2222-2222-222222222222"),
                        "reason", "approved by reviewer"
                ),
                List.of("LOW_CONFIDENCE"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-30T00:00:00Z")
        );
    }

    private static ReviewTask sampleBranchTask(String reviewId) {
        return new ReviewTask(
                reviewId,
                "req-branch",
                "post-branch",
                "trace-branch",
                ReviewTask.Status.PENDING,
                "BRANCH",
                List.of("candidate-branch"),
                Map.of(
                        "decision_id", "dec-branch-1",
                        "request_id", "req-branch",
                        "source_node_id", "33333333-3333-3333-3333-333333333333",
                        "content", "branch content",
                        "author_id", "user-branch",
                        "reason", "needs separate branch"
                ),
                List.of("LOW_CONFIDENCE"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-23T00:00:00Z"),
                Instant.parse("2026-03-30T00:00:00Z")
        );
    }

    private static ReviewTask sampleApprovedTask(String reviewId) {
        ReviewTask pendingTask = sampleMergeTask(reviewId);
        return new ReviewTask(
                pendingTask.reviewId(),
                pendingTask.requestId(),
                pendingTask.postNodeId(),
                pendingTask.workflowTraceId(),
                ReviewTask.Status.APPROVED,
                pendingTask.suggestedAction(),
                pendingTask.candidateNodeIds(),
                pendingTask.draftPayload(),
                pendingTask.reviewReasonCodes(),
                pendingTask.createdAt(),
                Instant.parse("2026-03-24T00:00:00Z"),
                Instant.parse("2026-03-31T00:00:00Z")
        );
    }

    private static ReviewTask sampleRejectedTask(String reviewId) {
        ReviewTask pendingTask = sampleTask(reviewId);
        return new ReviewTask(
                pendingTask.reviewId(),
                pendingTask.requestId(),
                pendingTask.postNodeId(),
                pendingTask.workflowTraceId(),
                ReviewTask.Status.REJECTED,
                pendingTask.suggestedAction(),
                pendingTask.candidateNodeIds(),
                pendingTask.draftPayload(),
                pendingTask.reviewReasonCodes(),
                pendingTask.createdAt(),
                Instant.parse("2026-03-24T00:00:00Z"),
                Instant.parse("2026-03-31T00:00:00Z")
        );
    }

    private static String generateTokenWithRole(String role) {
        return generateToken("review-operator", role);
    }

    private static String generateToken(String subject, String role) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of(role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS))
                .signWith(key)
                .compact();
    }
}
