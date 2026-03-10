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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AssociationLineageIsolationIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldExcludeConceptualOverlapFromLineage() {
        UUID ancestorId = UUID.randomUUID();
        UUID lineageNodeId = UUID.randomUUID();
        UUID semanticNodeId = UUID.randomUUID();
        createHumanPostNode(ancestorId, "req-ancestor", "author-a", "ancestor");
        createHumanPostNode(lineageNodeId, "req-lineage", "author-b", "lineage");
        createHumanPostNode(semanticNodeId, "req-semantic", "author-c", "semantic");
        createBranchedFrom(lineageNodeId, ancestorId);
        createAssociation(lineageNodeId, semanticNodeId, "CONCEPTUAL_OVERLAP");

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/nodes/" + lineageNodeId + "/lineage?max_depth=10",
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> topology = (Map<String, Object>) response.getBody().get("data");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");
        assertThat(nodes).extracting(item -> item.get("node_id")).contains(ancestorId.toString());
        assertThat(nodes).extracting(item -> item.get("node_id")).doesNotContain(semanticNodeId.toString());
    }

    @Test
    void shouldExcludeRelatesToFromProvenance() {
        UUID sourceId = UUID.randomUUID();
        UUID semanticId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPostNode(sourceId, "req-source", "author-source", "source");
        createHumanPostNode(semanticId, "req-sem", "author-sem", "semantic");
        createAIConsensusNode(consensusId, "summary", "gpt-4.1");
        createSynthesizedFrom(consensusId, sourceId);
        createAssociation(consensusId, semanticId, "RELATES_TO");

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/nodes/" + consensusId + "/provenance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).extracting(item -> item.get("node_id")).contains(sourceId.toString());
        assertThat(data).extracting(item -> item.get("node_id")).doesNotContain(semanticId.toString());
    }

    @Test
    void shouldAllowMergeDecisionWhenSemanticCycleExists() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        createHumanPostNode(nodeA, "req-a", "author-a", "A");
        createHumanPostNode(nodeB, "req-b", "author-b", "B");
        createAssociation(nodeA, nodeB, "CONCEPTUAL_OVERLAP");
        createAssociation(nodeB, nodeA, "CONCEPTUAL_OVERLAP");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/decisions/merge",
                mergeRequest("merge-semantic-cycle-001", nodeA, List.of(nodeB)),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("code")).isEqualTo(0);
    }

    private void createAssociation(UUID sourceNodeId, UUID targetNodeId, String type) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/associations",
                associationRequest(sourceNodeId, targetNodeId, type),
                Map.class
        );
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
    }

    private Map<String, Object> mergeRequest(String decisionId, UUID sourceNodeId, List<UUID> synthesizedFrom) {
        return Map.of(
                "decision_id", decisionId,
                "request_id", "req-" + decisionId,
                "source_node_id", sourceNodeId.toString(),
                "agent_version", "gpt-4.1",
                "summary_content", "summary",
                "synthesized_from", synthesizedFrom.stream().map(UUID::toString).toList(),
                "operator_type", "AGENT",
                "operator_id", "agent-1",
                "reason", "merge"
        );
    }

    private Map<String, Object> associationRequest(UUID sourceNodeId, UUID targetNodeId, String type) {
        Map<String, Object> request = new HashMap<>();
        request.put("source_node_id", sourceNodeId.toString());
        request.put("target_node_id", targetNodeId.toString());
        request.put("type", type);
        request.put("creator_id", "creator-1");
        request.put("reason", "isolation test");
        return request;
    }

    private void createHumanPostNode(UUID nodeId, String requestId, String authorId, String content) {
        neo4jClient.query("""
                CREATE (:Human_Post:GraphNode {
                  node_id: $nodeId,
                  request_id: $requestId,
                  author_id: $authorId,
                  content: $content,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(requestId).to("requestId")
                .bind(authorId).to("authorId")
                .bind(content).to("content")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createAIConsensusNode(UUID nodeId, String summaryContent, String agentVersion) {
        neo4jClient.query("""
                CREATE (:AI_Consensus:GraphNode {
                  node_id: $nodeId,
                  summary_content: $summaryContent,
                  agent_version: $agentVersion,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(summaryContent).to("summaryContent")
                .bind(agentVersion).to("agentVersion")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createBranchedFrom(UUID childId, UUID ancestorId) {
        neo4jClient.query("""
                MATCH (child:GraphNode {node_id: $childId}), (ancestor:GraphNode {node_id: $ancestorId})
                CREATE (child)-[:BRANCHED_FROM {
                    operator_type: 'HUMAN',
                    operator_id: 'tester',
                    created_at: $createdAt,
                    reason: 'lineage'
                }]->(ancestor)
                """)
                .bind(childId.toString()).to("childId")
                .bind(ancestorId.toString()).to("ancestorId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private void createSynthesizedFrom(UUID consensusId, UUID sourceId) {
        neo4jClient.query("""
                MATCH (consensus:AI_Consensus {node_id: $consensusId}), (source:Human_Post {node_id: $sourceId})
                CREATE (consensus)-[:SYNTHESIZED_FROM {
                    operator_type: 'AGENT',
                    operator_id: 'tester',
                    created_at: $createdAt,
                    reason: 'provenance'
                }]->(source)
                """)
                .bind(consensusId.toString()).to("consensusId")
                .bind(sourceId.toString()).to("sourceId")
                .bind(nowUtc()).to("createdAt")
                .run();
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
