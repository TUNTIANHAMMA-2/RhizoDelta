package com.rhizodelta.core.service;

import com.rhizodelta.core.repository.HumanPostRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceAuthoringContractTest {
    @Test
    void createHumanPostShouldRejectMissingAuthorAccount() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        HumanPostRepository repository = mock(HumanPostRepository.class);
        PostService service = new PostService(neo4jClient, repository);

        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("MATCH (post:Human_Post {request_id: $requestId})")
        )).bind(anyString()).to("requestId").fetchAs(String.class).one()).thenReturn(Optional.empty());
        when(neo4jClient.query(argThat((String query) ->
                query != null && query.contains("MATCH (user:UserAccount {user_id: $authorId})")
        )).bind(anyString()).to("authorId").fetch().one()).thenReturn(Optional.of(Map.of("exists", false)));

        PostService.CreateHumanPostCommand command = new PostService.CreateHumanPostCommand(
                "req-missing-author",
                "ghost-author",
                "content"
        );

        assertThatThrownBy(() -> service.createHumanPost(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("author_id not found");

        verify(neo4jClient, never()).query(argThat((String query) ->
                query != null && query.contains("MERGE (post:Human_Post {request_id: $requestId})")
        ));
        verify(repository, never()).findByNodeId(any());
    }
}
