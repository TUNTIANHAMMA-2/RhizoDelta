package com.rhizodelta.ai.quality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.rhizodelta.ai.quality.domain.QualityEvaluationCommand;
import com.rhizodelta.ai.quality.domain.QualityScore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityAgentServiceUnitTest {

    private static Neo4jClient mockNeo4jClient() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", "test")));
        return neo4jClient;
    }

    @Test
    void shouldParseQualityScoreFromLlmResponse() {
        UUID nodeId = UUID.randomUUID();
        ModelRouterService modelRouter = mock(ModelRouterService.class);
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);

        when(modelRouter.getModel(ModelPurpose.QUALITY)).thenReturn(chatModel);
        when(modelRouter.resolveModelName(ModelPurpose.QUALITY)).thenReturn("test-quality-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"relevance":0.85,"density":0.70,"argumentation":0.90,"community_value":0.60,"overall":0.78,"reason":"well-structured argument"}
                """)));

        QualityAgentService service = new QualityAgentService(modelRouter, new ObjectMapper(), mockNeo4jClient());

        QualityScore score = service.evaluate(new QualityEvaluationCommand(nodeId, "test content", "", ""));

        assertThat(score.relevance()).isEqualTo(0.85);
        assertThat(score.density()).isEqualTo(0.70);
        assertThat(score.argumentation()).isEqualTo(0.90);
        assertThat(score.communityValue()).isEqualTo(0.60);
        assertThat(score.overall()).isEqualTo(0.78);
        assertThat(score.reason()).isEqualTo("well-structured argument");
    }

    @Test
    void shouldClampOutOfRangeScores() {
        UUID nodeId = UUID.randomUUID();
        ModelRouterService modelRouter = mock(ModelRouterService.class);
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);

        when(modelRouter.getModel(ModelPurpose.QUALITY)).thenReturn(chatModel);
        when(modelRouter.resolveModelName(ModelPurpose.QUALITY)).thenReturn("test-quality-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {"relevance":1.5,"density":-0.2,"argumentation":0.5,"community_value":0.5,"overall":0.5,"reason":"clamped"}
                """)));

        QualityAgentService service = new QualityAgentService(modelRouter, new ObjectMapper(), mockNeo4jClient());

        QualityScore score = service.evaluate(new QualityEvaluationCommand(nodeId, "test content", "", ""));

        assertThat(score.relevance()).isEqualTo(1.0);
        assertThat(score.density()).isEqualTo(0.0);
    }

    @Test
    void shouldThrowOnUnparseableResponse() {
        UUID nodeId = UUID.randomUUID();
        ModelRouterService modelRouter = mock(ModelRouterService.class);
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);

        when(modelRouter.getModel(ModelPurpose.QUALITY)).thenReturn(chatModel);
        when(modelRouter.resolveModelName(ModelPurpose.QUALITY)).thenReturn("test-quality-model");
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("not json")));

        QualityAgentService service = new QualityAgentService(modelRouter, new ObjectMapper(), mockNeo4jClient());

        assertThatThrownBy(() -> service.evaluate(new QualityEvaluationCommand(nodeId, "test content", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to parse quality evaluation response");
    }
}
