package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostApiIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

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
                .bind(duplicateNodeId.toString())
                .to("nodeId")
                .bind(nowUtc())
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
                .bind(duplicateNodeId.toString())
                .to("nodeId")
                .bind(nowUtc())
                .to("createdAt")
                .run())
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldGetHumanPostById() {
        UUID nodeId = UUID.randomUUID();
        createHumanPostNode(nodeId, "req-get-human", "author-human", "human content");

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + nodeId, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("label")).isEqualTo("Human_Post");
        assertThat(data.get("content")).isEqualTo("human content");
    }

    @Test
    void shouldGetAIConsensusById() {
        UUID nodeId = UUID.randomUUID();
        neo4jClient.query("""
                CREATE (:AI_Consensus {
                  node_id: $nodeId,
                  summary_content: 'summary',
                  agent_version: 'v1',
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString())
                .to("nodeId")
                .bind(nowUtc())
                .to("createdAt")
                .run();

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + nodeId, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("label")).isEqualTo("AI_Consensus");
        assertThat(data.get("summary_content")).isEqualTo("summary");
    }

    @Test
    void shouldGetLineageByBranchedFrom() {
        UUID ancestorId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        createHumanPostNode(ancestorId, "req-ancestor", "author-a", "ancestor");
        createHumanPostNode(childId, "req-child", "author-b", "child");

        neo4jClient.query("""
                MATCH (child:Human_Post {node_id: $childId}), (ancestor:Human_Post {node_id: $ancestorId})
                CREATE (child)-[:BRANCHED_FROM {
                  operator_type: 'HUMAN',
                  operator_id: 'tester',
                  created_at: $createdAt,
                  reason: 'branch'
                }]->(ancestor)
                """)
                .bind(childId.toString())
                .to("childId")
                .bind(ancestorId.toString())
                .to("ancestorId")
                .bind(nowUtc())
                .to("createdAt")
                .run();

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + childId + "/lineage?max_depth=10", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).extracting(item -> item.get("node_id")).contains(ancestorId.toString());
    }

    @Test
    void shouldGetProvenanceFromConsensus() {
        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPostNode(sourceA, "req-source-a", "author-a", "source a");
        createHumanPostNode(sourceB, "req-source-b", "author-b", "source b");

        neo4jClient.query("""
                CREATE (:AI_Consensus {
                  node_id: $consensusId,
                  summary_content: 'combined',
                  agent_version: 'v1',
                  created_at: $createdAt
                })
                """)
                .bind(consensusId.toString())
                .to("consensusId")
                .bind(nowUtc())
                .to("createdAt")
                .run();

        neo4jClient.query("""
                MATCH (consensus:AI_Consensus {node_id: $consensusId}),
                      (a:Human_Post {node_id: $sourceA}),
                      (b:Human_Post {node_id: $sourceB})
                CREATE (consensus)-[:SYNTHESIZED_FROM {
                    operator_type: 'AGENT',
                    operator_id: 'agent-1',
                    created_at: $createdAt,
                    reason: 'summary'
                }]->(a)
                CREATE (consensus)-[:SYNTHESIZED_FROM {
                    operator_type: 'AGENT',
                    operator_id: 'agent-1',
                    created_at: $createdAt,
                    reason: 'summary'
                }]->(b)
                """)
                .bind(consensusId.toString())
                .to("consensusId")
                .bind(sourceA.toString())
                .to("sourceA")
                .bind(sourceB.toString())
                .to("sourceB")
                .bind(nowUtc())
                .to("createdAt")
                .run();

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + consensusId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).extracting(item -> item.get("node_id")).contains(sourceA.toString(), sourceB.toString());
    }

    private void createHumanPostNode(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                CREATE (:Human_Post {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString())
                .to("nodeId")
                .bind(requestId)
                .to("requestId")
                .bind(authorId)
                .to("authorId")
                .bind(content)
                .to("content")
                .bind(nowUtc())
                .to("createdAt")
                .run();
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
