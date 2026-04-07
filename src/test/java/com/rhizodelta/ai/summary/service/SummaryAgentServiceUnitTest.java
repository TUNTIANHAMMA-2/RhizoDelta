package com.rhizodelta.ai.summary.service;

import com.rhizodelta.ai.context.service.BranchContextService;
import com.rhizodelta.ai.context.service.EmbeddingService;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.ai.shared.service.EmbeddingModelService;
import com.rhizodelta.ai.shared.service.ModelRouterService;
import com.rhizodelta.ai.summary.domain.SummaryResult;
import com.rhizodelta.consensus.repository.AIConsensusRepository;
import com.rhizodelta.core.repository.HumanPostRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
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

class SummaryAgentServiceUnitTest {

    private static Neo4jClient mockNeo4jClient() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())
                .bind(any()).to(anyString())
                .bind(any()).to(anyString())
                .fetch().one())
                .thenReturn(Optional.of(Map.of("nodeId", "test")));
        return neo4jClient;
    }

    @Test
    void shouldGenerateSummaryFromSourcePosts() {
        UUID nodeId = UUID.randomUUID();
        ModelRouterService modelRouter = mock(ModelRouterService.class);
        AIConsensusRepository aiConsensusRepo = mock(AIConsensusRepository.class);
        EmbeddingModelService embeddingModelService = mock(EmbeddingModelService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        BranchContextService branchContextService = mock(BranchContextService.class);
        HumanPostRepository humanPostRepository = mock(HumanPostRepository.class);
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);

        when(modelRouter.getModel(ModelPurpose.SUMMARY)).thenReturn(chatModel);
        when(modelRouter.resolveModelName(ModelPurpose.SUMMARY)).thenReturn("test-summary-model");

        when(aiConsensusRepo.findProvenanceContents(nodeId)).thenReturn(List.of("First source content", "Second source content"));
        when(aiConsensusRepo.findSummaryContentByNodeId(nodeId)).thenReturn(Optional.empty());
        when(aiConsensusRepo.findMergedIntoTargetId(nodeId)).thenReturn(Optional.empty());
        when(chatModel.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("Combined summary of both sources.")));
        when(embeddingModelService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));

        SummaryAgentService service = new SummaryAgentService(
                modelRouter, aiConsensusRepo, mockNeo4jClient(), embeddingModelService, embeddingService,
                branchContextService, humanPostRepository, 4096);

        SummaryResult result = service.generate(nodeId);

        assertThat(result.summary()).isEqualTo("Combined summary of both sources.");
        assertThat(result.sourceCount()).isEqualTo(2);
        assertThat(result.modelUsed()).isEqualTo("test-summary-model");
    }

    @Test
    void shouldThrowWhenNoSourcePostsFound() {
        UUID nodeId = UUID.randomUUID();
        ModelRouterService modelRouter = mock(ModelRouterService.class);
        AIConsensusRepository aiConsensusRepo = mock(AIConsensusRepository.class);
        EmbeddingModelService embeddingModelService = mock(EmbeddingModelService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        BranchContextService branchContextService = mock(BranchContextService.class);
        HumanPostRepository humanPostRepository = mock(HumanPostRepository.class);
        when(aiConsensusRepo.findProvenanceContents(nodeId)).thenReturn(List.of());

        SummaryAgentService service = new SummaryAgentService(
                modelRouter, aiConsensusRepo, mockNeo4jClient(), embeddingModelService, embeddingService,
                branchContextService, humanPostRepository, 4096);

        assertThatThrownBy(() -> service.generate(nodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no source posts found");
    }
}
