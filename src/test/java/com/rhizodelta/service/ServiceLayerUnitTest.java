package com.rhizodelta.service;

import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceLayerUnitTest {
    @Mock
    private HumanPostRepository humanPostRepository;

    @Mock
    private AIConsensusRepository aiConsensusRepository;

    @Test
    void createHumanPostCommandShouldRejectBlankFields() {
        assertThatThrownBy(() -> new PostService.CreateHumanPostCommand(" ", "author", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");

        assertThatThrownBy(() -> new PostService.CreateHumanPostCommand("req-1", " ", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorId");

        assertThatThrownBy(() -> new PostService.CreateHumanPostCommand("req-1", "author", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void postServiceShouldReturnPersistedNodeFromUpsertResult() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        PostService postService = new PostService(neo4jClient, humanPostRepository);

        UUID persistedNodeId = UUID.randomUUID();
        HumanPost persisted = HumanPost.create(persistedNodeId, "content", "author", "req-001");

        when(neo4jClient.query(anyString())
                .bind(eq("req-001")).to(eq("requestId"))
                .bind(org.mockito.ArgumentMatchers.<Object>any()).to(eq("nodeId"))
                .bind(eq("content")).to(eq("content"))
                .bind(eq("author")).to(eq("authorId"))
                .bind(org.mockito.ArgumentMatchers.<Object>any()).to(eq("createdAt"))
                .fetchAs(String.class)
                .one()).thenReturn(Optional.of(persistedNodeId.toString()));
        when(humanPostRepository.findByNodeId(persistedNodeId)).thenReturn(Optional.of(persisted));

        HumanPost result = postService.createHumanPost(new PostService.CreateHumanPostCommand("req-001", "author", "content"));

        assertThat(result.getNodeId()).isEqualTo(persistedNodeId);
        assertThat(result.getRequestId()).isEqualTo("req-001");
    }

    @Test
    void getNodeByIdShouldReturnHumanPostNode() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();
        HumanPost humanPost = HumanPost.create(nodeId, "hello", "author", "req-hello");

        when(humanPostRepository.findByNodeId(nodeId)).thenReturn(Optional.of(humanPost));

        NodeQueryService.NodeResult result = service.getNodeById(nodeId);

        assertThat(result).isInstanceOf(NodeQueryService.HumanPostNode.class);
    }

    @Test
    void getNodeByIdShouldThrowNoSuchElementWhenNotFound() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();

        when(humanPostRepository.findByNodeId(nodeId)).thenReturn(Optional.empty());
        when(aiConsensusRepository.findByNodeId(nodeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNodeById(nodeId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void getNodeByIdShouldReturnAIConsensusNodeWhenHumanMissing() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();
        AIConsensus aiConsensus = AIConsensus.create(nodeId, "summary", "v1");

        when(humanPostRepository.findByNodeId(nodeId)).thenReturn(Optional.empty());
        when(aiConsensusRepository.findByNodeId(nodeId)).thenReturn(Optional.of(aiConsensus));

        NodeQueryService.NodeResult result = service.getNodeById(nodeId);

        assertThat(result).isInstanceOf(NodeQueryService.AIConsensusNode.class);
    }

    @Test
    void getLineageShouldUseDefaultDepthWhenInputIsNull() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();
        when(humanPostRepository.findLineage(nodeId, 10)).thenReturn(List.of());

        service.getLineage(nodeId, null);

        org.mockito.Mockito.verify(humanPostRepository).findLineage(nodeId, 10);
    }

    @Test
    void getLineageShouldRejectNonPositiveDepth() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);

        assertThatThrownBy(() -> service.getLineage(UUID.randomUUID(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    void getProvenanceShouldRequireConsensusNodeExists() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();
        when(aiConsensusRepository.findByNodeId(nodeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProvenance(nodeId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("AI_Consensus");
    }

    @Test
    void getProvenanceShouldReturnHumanPostsForConsensus() {
        NodeQueryService service = new NodeQueryService(humanPostRepository, aiConsensusRepository);
        UUID nodeId = UUID.randomUUID();
        HumanPost source = HumanPost.create(UUID.randomUUID(), "source", "author", "req-src");

        when(aiConsensusRepository.findByNodeId(nodeId)).thenReturn(Optional.of(AIConsensus.create(nodeId, "summary", "v1")));
        when(humanPostRepository.findProvenance(nodeId)).thenReturn(List.of(source));

        List<HumanPost> provenance = service.getProvenance(nodeId);

        assertThat(provenance).hasSize(1);
        assertThat(provenance.get(0).getNodeId()).isEqualTo(source.getNodeId());
    }
}
