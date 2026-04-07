package com.rhizodelta.ai.routing.service;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.service.DecisionService;
import com.rhizodelta.core.domain.node.HumanPost;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(decisionService.mergeOrAppend(any(MergeDecisionCommand.class)))
                .thenReturn(new DecisionService.MergeOrAppendResult(decisionResult, false));
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
        verify(decisionService).mergeOrAppend(commandCaptor.capture());
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
    void shouldLinkExistingNodeAsBranchInsteadOfCreatingNew() {
        DecisionService decisionService = mock(DecisionService.class);
        UUID postNodeId = UUID.randomUUID();
        UUID sourceNodeId = UUID.randomUUID();
        HumanPost post = HumanPost.create(postNodeId, "branch content", "author-2", "req-2");
        DecisionResult decisionResult = new DecisionResult("evt-2:branch", postNodeId, "QUEUED");
        when(decisionService.linkBranch(
                anyString(), eq(postNodeId), eq(sourceNodeId),
                eq(DecisionOperatorType.AGENT), anyString(), anyString(), anyList()
        )).thenReturn(decisionResult);
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

        verify(decisionService).linkBranch(
                eq("evt-2:branch"),
                eq(postNodeId),
                eq(sourceNodeId),
                eq(DecisionOperatorType.AGENT),
                eq("ai-routing-orchestrator"),
                eq("extends the recalled node"),
                eq(List.of(postNodeId))
        );
        assertThat(result.action()).isEqualTo("BRANCH");
        assertThat(result.decisionResult().node_id()).isEqualTo(postNodeId);
    }
}
