package com.rhizodelta.api;

import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.ForkDecisionResult;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.config.JwtAuthenticationFilter;
import com.rhizodelta.config.SecurityConfig;
import com.rhizodelta.exception.DagIntegrityViolationException;
import com.rhizodelta.service.DecisionService;
import com.rhizodelta.service.RollbackService;
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
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DecisionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class DecisionControllerWebTest {
    private static final String TEST_SECRET =
            "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DecisionService decisionService;

    @MockBean
    private RollbackService rollbackService;

    @Test
    void shouldAcceptMergeDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeMerge(any()))
                .thenReturn(new DecisionResult("dec-merge-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/merge", validMergeJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-merge-1"))
                .andExpect(jsonPath("$.data.node_id").value(nodeId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void shouldAcceptBranchDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeBranch(any()))
                .thenReturn(new DecisionResult("dec-branch-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/branch", validBranchJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-branch-1"))
                .andExpect(jsonPath("$.data.node_id").value(nodeId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void shouldReturnConflictWhenDagCycleDetected() throws Exception {
        when(decisionService.executeMerge(any(MergeDecisionCommand.class)))
                .thenThrow(new DagIntegrityViolationException("cycle detected"));

        authorizedPost("/api/decisions/merge", validMergeJson())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("cycle detected"));
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() throws Exception {
        authorizedPost("/api/decisions/merge", "{\"decision_id\":\"broken\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldReturn403WhenUserRoleCallsMerge() throws Exception {
        String userToken = generateTokenWithRole("USER");
        mockMvc.perform(post("/api/decisions/merge")
                        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMergeJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("insufficient permissions"));
    }

    // --- Inject ---

    @Test
    void shouldAcceptInjectDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeInject(any()))
                .thenReturn(new DecisionResult("dec-inject-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/inject", validInjectJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-inject-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void shouldReturn403WhenUserRoleCallsInject() throws Exception {
        String userToken = generateTokenWithRole("USER");
        mockMvc.perform(post("/api/decisions/inject")
                        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInjectJson()))
                .andExpect(status().isForbidden());
    }

    // --- Materialize ---

    @Test
    void shouldAcceptMaterializeDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeMaterialize(any()))
                .thenReturn(new DecisionResult("dec-mat-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/materialize", validMaterializeJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-mat-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    // --- Fork ---

    @Test
    void shouldAcceptForkDecisionRequest() throws Exception {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        when(decisionService.executeFork(any()))
                .thenReturn(new ForkDecisionResult("fork-op-1", List.of(n1, n2), "QUEUED", 2, 2));

        authorizedPost("/api/decisions/fork", validForkJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.operation_id").value("fork-op-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.created_count").value(2));
    }

    // --- CrossSynth ---

    @Test
    void shouldAcceptCrossSynthDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeCrossSynth(any()))
                .thenReturn(new DecisionResult("dec-cs-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/cross-synth", validCrossSynthJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-cs-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    // --- Join ---

    @Test
    void shouldAcceptJoinDecisionRequest() throws Exception {
        UUID nodeId = UUID.randomUUID();
        when(decisionService.executeJoin(any()))
                .thenReturn(new DecisionResult("dec-join-1", nodeId, "QUEUED"));

        authorizedPost("/api/decisions/join", validJoinJson())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision_id").value("dec-join-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    // --- Fork Rollback ---

    @Test
    void shouldAcceptForkRollbackWithAdminRole() throws Exception {
        UUID n1 = UUID.randomUUID();
        when(rollbackService.rollbackForkByOperationId("fork-op-1"))
                .thenReturn(new RollbackService.ForkRollbackResult(
                        "fork-op-1", List.of(n1), 1L, true, 1));

        String adminToken = generateTokenWithRole("ADMIN");
        mockMvc.perform(post("/api/decisions/fork/fork-op-1/rollback")
                        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.operation_id").value("fork-op-1"));
    }

    @Test
    void shouldReturn403WhenAgentRoleCallsForkRollback() throws Exception {
        mockMvc.perform(post("/api/decisions/fork/fork-op-1/rollback")
                        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + generateValidToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Malformed JSON for new endpoints ---

    @Test
    void shouldReturnBadRequestForMalformedInjectJson() throws Exception {
        authorizedPost("/api/decisions/inject", "{\"decision_id\":\"broken\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    private ResultActions authorizedPost(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + generateValidToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String generateValidToken() {
        return generateTokenWithRole("AGENT");
    }

    private String generateTokenWithRole(String role) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("user-1")
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(TOKEN_TTL_MILLIS)))
                .signWith(key)
                .compact();
    }

    private static String validMergeJson() {
        return """
                {
                  "decision_id": "dec-merge-1",
                  "request_id": "req-merge-1",
                  "source_node_id": "7a8ecf7f-a95f-49a4-aa53-c99710f3cfe1",
                  "agent_version": "gpt-4.1",
                  "summary_content": "summary",
                  "synthesized_from": [
                    "ab44438f-6d2f-4da2-9015-a772f96b5f8a"
                  ],
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "merge"
                }
                """;
    }

    private static String validBranchJson() {
        return """
                {
                  "decision_id": "dec-branch-1",
                  "request_id": "req-branch-1",
                  "source_node_id": "7a8ecf7f-a95f-49a4-aa53-c99710f3cfe1",
                  "content": "branch content",
                  "author_id": "author-1",
                  "operator_type": "HUMAN",
                  "operator_id": "human-1",
                  "reason": "branch"
                }
                """;
    }

    private static String validInjectJson() {
        return """
                {
                  "decision_id": "dec-inject-1",
                  "request_id": "req-inject-1",
                  "source_node_id": "7a8ecf7f-a95f-49a4-aa53-c99710f3cfe1",
                  "content": "inject content",
                  "author_id": "author-1",
                  "operator_type": "HUMAN",
                  "operator_id": "human-1",
                  "reason": "inject"
                }
                """;
    }

    private static String validMaterializeJson() {
        return """
                {
                  "decision_id": "dec-mat-1",
                  "request_id": "req-mat-1",
                  "source_node_id": "7a8ecf7f-a95f-49a4-aa53-c99710f3cfe1",
                  "content": "materialized content",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "materialize"
                }
                """;
    }

    private static String validForkJson() {
        return """
                {
                  "operation_id": "fork-op-1",
                  "request_id": "req-fork-1",
                  "source_node_id": "7a8ecf7f-a95f-49a4-aa53-c99710f3cfe1",
                  "branches": [
                    {"decision_id": "fork-b1", "content": "branch A", "author_id": "author-a"},
                    {"decision_id": "fork-b2", "content": "branch B", "author_id": "author-b"}
                  ],
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "fork"
                }
                """;
    }

    private static String validCrossSynthJson() {
        return """
                {
                  "decision_id": "dec-cs-1",
                  "request_id": "req-cs-1",
                  "source_result_ids": [
                    "ab44438f-6d2f-4da2-9015-a772f96b5f8a",
                    "bc55549f-7e3f-5eb3-a126-b883a17c4a9b"
                  ],
                  "content": "cross-synth content",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "cross-synth"
                }
                """;
    }

    private static String validJoinJson() {
        return """
                {
                  "decision_id": "dec-join-1",
                  "request_id": "req-join-1",
                  "source_node_ids": [
                    "ab44438f-6d2f-4da2-9015-a772f96b5f8a",
                    "cd66660f-8f4f-4fc4-b237-c994b28d5a0c"
                  ],
                  "summary_content": "joined summary",
                  "agent_version": "gpt-4.1",
                  "operator_type": "AGENT",
                  "operator_id": "agent-1",
                  "reason": "join"
                }
                """;
    }
}
