package com.rhizodelta.service;

import com.rhizodelta.domain.ai.ModelPurpose;
import com.rhizodelta.domain.ai.SummaryResult;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
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

        HumanPost source1 = HumanPost.create(UUID.randomUUID(), "First source content", "author1", "req1");
        HumanPost source2 = HumanPost.create(UUID.randomUUID(), "Second source content", "author2", "req2");
        when(aiConsensusRepo.findProvenance(nodeId)).thenReturn(List.of(source1, source2));
        when(aiConsensusRepo.findActiveByNodeId(nodeId)).thenReturn(Optional.empty());
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
        when(aiConsensusRepo.findProvenance(nodeId)).thenReturn(List.of());

        SummaryAgentService service = new SummaryAgentService(
                modelRouter, aiConsensusRepo, mockNeo4jClient(), embeddingModelService, embeddingService,
                branchContextService, humanPostRepository, 4096);

        assertThatThrownBy(() -> service.generate(nodeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no source posts found");
    }
}
