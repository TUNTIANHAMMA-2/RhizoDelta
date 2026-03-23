package com.rhizodelta.service;

import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.node.HumanPost;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRoutingExecutionServiceUnitTest {

    @Test
    void shouldTranslateMergeActionIntoDecisionCommand() {
        DecisionService decisionService = mock(DecisionService.class);
        UUID postNodeId = UUID.randomUUID();
        UUID sourceNodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(postNodeId, "merged content", "author-1", "req-1");
        DecisionResult decisionResult = new DecisionResult("evt-1:merge", UUID.randomUUID(), "QUEUED");
        when(decisionService.executeMerge(any(MergeDecisionCommand.class))).thenReturn(decisionResult);
        AiRoutingExecutionService service = new AiRoutingExecutionService(decisionService, "Qwen/Qwen2.5-7B-Instruct");

        AiRoutingExecutionService.RoutingExecutionResult result = service.execute(
                new AiRoutingExecutionService.RoutingExecutionCommand(
                        "req-1",
                        "evt-1",
                        sourceNodeId.toString(),
                        "MERGE",
                        "same knowledge unit",
                        post
                )
        );

        ArgumentCaptor<MergeDecisionCommand> commandCaptor = ArgumentCaptor.forClass(MergeDecisionCommand.class);
        verify(decisionService).executeMerge(commandCaptor.capture());
        MergeDecisionCommand command = commandCaptor.getValue();
        assertThat(command.decision_id()).isEqualTo("evt-1:merge");
        assertThat(command.request_id()).isEqualTo("req-1");
        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
        assertThat(command.agent_version()).isEqualTo("Qwen/Qwen2.5-7B-Instruct");
        assertThat(command.summary_content()).isEqualTo("merged content");
        assertThat(command.synthesized_from()).containsExactly(postNodeId);
        assertThat(command.operator_type()).isEqualTo(DecisionOperatorType.AGENT);
        assertThat(command.operator_id()).isEqualTo("ai-routing-orchestrator");
        assertThat(command.reason()).isEqualTo("same knowledge unit");
        assertThat(result.action()).isEqualTo("MERGE");
        assertThat(result.decisionResult()).isEqualTo(decisionResult);
    }

    @Test
    void shouldTranslateBranchActionIntoDecisionCommand() {
        DecisionService decisionService = mock(DecisionService.class);
        UUID postNodeId = UUID.randomUUID();
        UUID sourceNodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(postNodeId, "branch content", "author-2", "req-2");
        DecisionResult decisionResult = new DecisionResult("evt-2:branch", UUID.randomUUID(), "QUEUED");
        when(decisionService.executeBranch(any(BranchDecisionCommand.class))).thenReturn(decisionResult);
        AiRoutingExecutionService service = new AiRoutingExecutionService(decisionService, "Qwen/Qwen2.5-7B-Instruct");

        AiRoutingExecutionService.RoutingExecutionResult result = service.execute(
                new AiRoutingExecutionService.RoutingExecutionCommand(
                        "req-2",
                        "evt-2",
                        sourceNodeId.toString(),
                        "BRANCH",
                        "extends the recalled node",
                        post
                )
        );

        ArgumentCaptor<BranchDecisionCommand> commandCaptor = ArgumentCaptor.forClass(BranchDecisionCommand.class);
        verify(decisionService).executeBranch(commandCaptor.capture());
        BranchDecisionCommand command = commandCaptor.getValue();
        assertThat(command.decision_id()).isEqualTo("evt-2:branch");
        assertThat(command.request_id()).isEqualTo("req-2");
        assertThat(command.source_node_id()).isEqualTo(sourceNodeId);
        assertThat(command.content()).isEqualTo("branch content");
        assertThat(command.author_id()).isEqualTo("author-2");
        assertThat(command.operator_type()).isEqualTo(DecisionOperatorType.AGENT);
        assertThat(command.operator_id()).isEqualTo("ai-routing-orchestrator");
        assertThat(command.reason()).isEqualTo("extends the recalled node");
        assertThat(result.action()).isEqualTo("BRANCH");
        assertThat(result.decisionResult()).isEqualTo(decisionResult);
    }
}
