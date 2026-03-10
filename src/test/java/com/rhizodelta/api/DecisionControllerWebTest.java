package com.rhizodelta.api;

import com.rhizodelta.exception.DagIntegrityViolationException;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.service.DecisionService;
import com.rhizodelta.service.RollbackService;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DecisionController.class)
class DecisionControllerWebTest {

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

        mockMvc.perform(post("/api/decisions/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMergeJson()))
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

        mockMvc.perform(post("/api/decisions/branch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBranchJson()))
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

        mockMvc.perform(post("/api/decisions/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMergeJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("cycle detected"));
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/decisions/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision_id\":\"broken\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
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
}
