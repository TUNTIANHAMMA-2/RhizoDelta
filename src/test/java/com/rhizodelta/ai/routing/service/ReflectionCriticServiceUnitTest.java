package com.rhizodelta.ai.routing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.rhizodelta.ai.routing.domain.ReflectionResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReflectionCriticServiceUnitTest {

    @Test
    void shouldReturnConfirmedWhenCriticAgrees() {
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"confirmed":true,"revisedAction":"MERGE","revisedConfidence":0.91,"criticReason":"decision is well-justified"}
                """)));
        ReflectionCriticService service = new ReflectionCriticService(modelRouterService, new ObjectMapper());

        ReflectionResult result = service.critique("MERGE", 0.91, "same knowledge unit", "post content", "context");

        assertThat(result.confirmed()).isTrue();
        assertThat(result.revisedAction()).isEqualTo("MERGE");
        assertThat(result.revisedConfidence()).isEqualTo(0.91);
        assertThat(result.criticReason()).isEqualTo("decision is well-justified");
    }

    @Test
    void shouldReturnNotConfirmedWhenCriticDisagrees() {
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"confirmed":false,"revisedAction":"REVIEW","revisedConfidence":0.45,"criticReason":"candidate scores are too close"}
                """)));
        ReflectionCriticService service = new ReflectionCriticService(modelRouterService, new ObjectMapper());

        ReflectionResult result = service.critique("MERGE", 0.91, "same knowledge unit", "post content", "context");

        assertThat(result.confirmed()).isFalse();
        assertThat(result.revisedAction()).isEqualTo("REVIEW");
        assertThat(result.revisedConfidence()).isEqualTo(0.45);
        assertThat(result.criticReason()).isEqualTo("candidate scores are too close");
    }

    @Test
    void shouldThrowWhenResponseIsUnparseable() {
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        ModelRouterService modelRouterService = mock(ModelRouterService.class);
        when(modelRouterService.getModel(ModelPurpose.ROUTING)).thenReturn(chatModel);
        when(modelRouterService.resolveModelName(ModelPurpose.ROUTING)).thenReturn("test-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("not json")));
        ReflectionCriticService service = new ReflectionCriticService(modelRouterService, new ObjectMapper());

        assertThatThrownBy(() -> service.critique("MERGE", 0.91, "reason", "content", "context"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to parse reflection critic response");
    }
}
