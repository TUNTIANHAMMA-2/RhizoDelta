package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PostApiIntegrationTest {
    @Container
    private static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.22")
            .withAdminPassword("test-password");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", NEO4J::getAdminPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldCreatePostAndReturnQueued() {
        Map<String, Object> request = Map.of(
                "request_id", "req-001",
                "author_id", "author-001",
                "content", "hello graph"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/posts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("QUEUED");
        assertThat(data.get("event_id")).isNotNull();
    }

    @Test
    void shouldReturnSameEventIdForDuplicateRequestId() {
        Map<String, Object> request = Map.of(
                "request_id", "req-duplicate",
                "author_id", "author-001",
                "content", "same request"
        );

        ResponseEntity<Map> first = restTemplate.postForEntity("/api/posts", request, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/posts", request, Map.class);

        Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
        Map<String, Object> secondData = (Map<String, Object>) second.getBody().get("data");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(firstData.get("event_id")).isEqualTo(secondData.get("event_id"));
    }

    @Test
    void shouldRejectDuplicateNodeIdByConstraint() {
        UUID duplicateNodeId = UUID.randomUUID();

        neo4jClient.query("""
                CREATE (:Human_Post {
                  node_id: $nodeId,
                  request_id: 'req-1',
                  author_id: 'author-1',
                  content: 'first',
                  created_at: $createdAt
                })
                """)
                .bind(duplicateNodeId)
                .to("nodeId")
                .bind(Instant.now())
                .to("createdAt")
                .run();

        assertThatThrownBy(() -> neo4jClient.query("""
                        CREATE (:Human_Post {
                          node_id: $nodeId,
                          request_id: 'req-2',
                          author_id: 'author-2',
                          content: 'second',
                          created_at: $createdAt
                        })
                        """)
                .bind(duplicateNodeId)
                .to("nodeId")
                .bind(Instant.now())
                .to("createdAt")
                .run())
                .isInstanceOf(Exception.class);
    }
}
