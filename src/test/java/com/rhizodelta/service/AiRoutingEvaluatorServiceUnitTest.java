package com.rhizodelta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.domain.ai.ModelPurpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRoutingEvaluatorServiceUnitTest {

    @Test
    void shouldParseMergeDecisionAboveThreshold() {
        ChatLanguageModel chatLanguageModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatLanguageModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"action":"MERGE","confidence":0.91,"reason":"same knowledge unit"}
                """)));
        AiRoutingEvaluatorService service = new AiRoutingEvaluatorService(
                modelRouterService,
                new ObjectMapper(),
                0.65d
        );

        AiRoutingEvaluatorService.RoutingEvaluation evaluation = service.evaluate(
                new AiRoutingEvaluatorService.RoutingEvaluationCommand(
                        "post content",
                        "node_id=n1 label=Human_Post score=0.95 content=candidate",
                        "target-1"
                )
        );

        assertThat(evaluation.action()).isEqualTo("MERGE");
        assertThat(evaluation.reason()).isEqualTo("same knowledge unit");
        assertThat(evaluation.confidence()).isEqualTo(0.91d);
        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue().toString()).contains("target-1", "post content", "candidate");
    }

    @Test
    void shouldDowngradeLowConfidenceDecisionToReview() {
        ChatLanguageModel chatLanguageModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatLanguageModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"action":"BRANCH","confidence":0.42,"reason":"extends the recalled node"}
                """)));
        AiRoutingEvaluatorService service = new AiRoutingEvaluatorService(
                modelRouterService,
                new ObjectMapper(),
                0.65d
        );

        AiRoutingEvaluatorService.RoutingEvaluation evaluation = service.evaluate(
                new AiRoutingEvaluatorService.RoutingEvaluationCommand("post content", "context", "")
        );

        assertThat(evaluation.action()).isEqualTo("REVIEW");
        assertThat(evaluation.reason()).contains("confidence 0.42 below threshold 0.65");
        assertThat(evaluation.confidence()).isEqualTo(0.42d);
    }

    @Test
    void shouldRejectUnsupportedAction() {
        ChatLanguageModel chatLanguageModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatLanguageModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatLanguageModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"action":"SKIP","confidence":0.91,"reason":"invalid"}
                """)));
        AiRoutingEvaluatorService service = new AiRoutingEvaluatorService(
                modelRouterService,
                new ObjectMapper(),
                0.65d
        );

        assertThatThrownBy(() -> service.evaluate(
                new AiRoutingEvaluatorService.RoutingEvaluationCommand("post content", "context", "")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported routing action");
    }
}
