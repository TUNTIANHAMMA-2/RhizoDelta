package com.rhizodelta.service;

import com.rhizodelta.query.api.CommentNode;
import com.rhizodelta.query.api.DiscussionArtifact;
import com.rhizodelta.query.api.DiscussionTreeResponse;
import com.rhizodelta.query.service.DiscussionTreeQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class DiscussionTreeQueryServiceTest {
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
    private Neo4jClient neo4jClient;

    @Autowired
    private DiscussionTreeQueryService discussionTreeQueryService;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldProjectNestedRepliesWithDepthParentAndSiblingOrder() {
        UUID rootId = UUID.randomUUID();
        UUID lateChildId = UUID.randomUUID();
        UUID earlyChildId = UUID.randomUUID();
        UUID grandchildId = UUID.randomUUID();
        UUID greatGrandchildId = UUID.randomUUID();
        createUserWithProfile("author-root", "root-user", "Root User");
        createUserWithProfile("author-early", "early-user", "Early User");
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createHumanPost(lateChildId, "req-late", "author-late", "late child", atMinute(20));
        createHumanPost(earlyChildId, "req-early", "author-early", "early child", atMinute(10));
        createHumanPost(grandchildId, "req-grand", "author-grand", "grand child", atMinute(30));
        createHumanPost(greatGrandchildId, "req-great", "author-great", "great grand child", atMinute(40));
        createContinuesFrom(lateChildId, rootId);
        createBranchedFrom(earlyChildId, rootId);
        createContinuesFrom(grandchildId, earlyChildId);
        createContinuesFrom(greatGrandchildId, grandchildId);

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, "caller", null);

        assertThat(response.meta().maxDepth()).isEqualTo(5);
        assertThat(response.root().nodeId()).isEqualTo(rootId.toString());
        assertThat(response.root().author().username()).isEqualTo("root-user");
        assertThat(response.root().author().displayName()).isEqualTo("Root User");
        assertThat(response.root().children()).extracting(CommentNode::nodeId)
                .containsExactly(earlyChildId.toString(), lateChildId.toString());

        CommentNode earlyChild = response.root().children().get(0);
        assertThat(earlyChild.parentId()).isEqualTo(rootId.toString());
        assertThat(earlyChild.depth()).isEqualTo(1);
        assertThat(earlyChild.author().username()).isEqualTo("early-user");
        CommentNode grandchild = earlyChild.children().get(0);
        assertThat(grandchild.parentId()).isEqualTo(earlyChildId.toString());
        assertThat(grandchild.depth()).isEqualTo(2);
        assertThat(grandchild.children().get(0).parentId()).isEqualTo(grandchildId.toString());
        assertThat(grandchild.children().get(0).depth()).isEqualTo(3);
    }

    @Test
    void shouldAttachConsensusAndKeepAllSourceNodeIds() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID sourceTwoId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createHumanPost(childId, "req-child", "author-child", "child", atMinute(1));
        createHumanPost(sourceTwoId, "req-source-2", "author-source", "source two", atMinute(2));
        createAiConsensus(consensusId, "summary body", "agent-v1", atMinute(3));
        createContinuesFrom(childId, rootId);
        createMergedInto(consensusId, rootId);
        createSynthesizedFrom(consensusId, rootId);
        createSynthesizedFrom(consensusId, childId);
        createSynthesizedFrom(consensusId, sourceTwoId);

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, null, null);

        assertThat(response.root().children()).extracting(CommentNode::nodeId).containsExactly(childId.toString());
        assertThat(response.root().artifacts()).hasSize(1);
        DiscussionArtifact artifact = response.root().artifacts().get(0);
        assertThat(artifact.kind()).isEqualTo("CONSENSUS");
        assertThat(artifact.body()).isEqualTo("summary body");
        assertThat(artifact.agentVersion()).isEqualTo("agent-v1");
        assertThat(artifact.sourceNodeIds()).containsExactly(rootId.toString(), childId.toString(), sourceTwoId.toString());
        assertThat(artifact.sourceCount()).isEqualTo(3);
    }

    @Test
    void shouldAttachResultAsArtifact() {
        UUID rootId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createResult(resultId, "result body", "agent-r1", atMinute(1));
        createMaterializedFrom(resultId, rootId);

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, null, null, null, null);

        assertThat(response.root().artifacts()).hasSize(1);
        DiscussionArtifact artifact = response.root().artifacts().get(0);
        assertThat(artifact.kind()).isEqualTo("RESULT");
        assertThat(artifact.body()).isEqualTo("result body");
        assertThat(artifact.sourceNodeIds()).isEmpty();
        assertThat(artifact.sourceCount()).isZero();
    }

    @Test
    void shouldFilterSoftDeletedChildrenAndConsensusArtifacts() {
        UUID rootId = UUID.randomUUID();
        UUID liveChildId = UUID.randomUUID();
        UUID deletedChildId = UUID.randomUUID();
        UUID liveConsensusId = UUID.randomUUID();
        UUID deletedConsensusId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createHumanPost(liveChildId, "req-live", "author-live", "live", atMinute(1));
        createHumanPost(deletedChildId, "req-deleted", "author-deleted", "deleted", atMinute(2));
        createAiConsensus(liveConsensusId, "live summary", "agent", atMinute(3));
        createAiConsensus(deletedConsensusId, "deleted summary", "agent", atMinute(4));
        createContinuesFrom(liveChildId, rootId);
        createContinuesFrom(deletedChildId, rootId);
        createMergedInto(liveConsensusId, rootId);
        createMergedInto(deletedConsensusId, rootId);
        markDeleted(deletedChildId);
        markDeleted(deletedConsensusId);

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, null, null);

        assertThat(response.root().children()).extracting(CommentNode::nodeId).containsExactly(liveChildId.toString());
        assertThat(response.root().artifacts()).extracting(DiscussionArtifact::nodeId).containsExactly(liveConsensusId.toString());
    }

    @Test
    void shouldApplyBfsTruncationAndChildCounts() {
        UUID rootId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        for (int i = 0; i < 30; i++) {
            UUID childId = UUID.randomUUID();
            createHumanPost(childId, "req-child-" + i, "author-child", "child " + i, atMinute(i + 1));
            createContinuesFrom(childId, rootId);
        }

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, 5, 10, null, null);

        assertThat(response.root().children()).hasSize(9);
        assertThat(response.root().totalChildrenCount()).isEqualTo(30);
        assertThat(response.root().hasMoreChildren()).isTrue();
        assertThat(response.root().children()).allSatisfy(child -> {
            assertThat(child.hasMoreChildren()).isFalse();
            assertThat(child.totalChildrenCount()).isZero();
        });
        assertThat(response.meta().truncated()).isTrue();
        assertThat(response.meta().hasMore()).isTrue();
    }

    @Test
    void shouldPreserveConsensusSourcesOutsideVisibleTree() {
        UUID rootId = UUID.randomUUID();
        UUID visibleSourceId = UUID.randomUUID();
        UUID externalRootId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createHumanPost(visibleSourceId, "req-visible", "author-visible", "visible", atMinute(1));
        createHumanPost(externalRootId, "req-ext", "author-ext", "external", atMinute(2));
        createAiConsensus(consensusId, "cross tree summary", "agent", atMinute(3));
        createContinuesFrom(visibleSourceId, rootId);
        createMergedInto(consensusId, rootId);
        createSynthesizedFrom(consensusId, visibleSourceId);
        createSynthesizedFrom(consensusId, externalRootId);

        DiscussionArtifact artifact = discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, null, null)
                .root()
                .artifacts()
                .get(0);

        assertThat(artifact.sourceNodeIds()).containsExactly(visibleSourceId.toString(), externalRootId.toString());
        assertThat(artifact.sourceCount()).isEqualTo(artifact.sourceNodeIds().size());
    }

    @Test
    void shouldAttachSharedConsensusToMultipleAnchors() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID consensusId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));
        createHumanPost(childId, "req-child", "author-child", "child", atMinute(1));
        createAiConsensus(consensusId, "shared summary", "agent", atMinute(2));
        createContinuesFrom(childId, rootId);
        createMergedInto(consensusId, rootId);
        createMergedInto(consensusId, childId);

        DiscussionTreeResponse response = discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, null, null);

        assertThat(response.root().artifacts()).extracting(DiscussionArtifact::nodeId).containsExactly(consensusId.toString());
        assertThat(response.root().children().get(0).artifacts()).extracting(DiscussionArtifact::nodeId).containsExactly(consensusId.toString());
    }

    @Test
    void shouldRejectNonHumanRoot() {
        UUID consensusId = UUID.randomUUID();
        createAiConsensus(consensusId, "summary", "agent", atMinute(0));

        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(consensusId, 5, 20, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Human_Post");
    }

    @Test
    void shouldRejectMissingOrDeletedRootAsNotFound() {
        UUID missingId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        createHumanPost(deletedId, "req-deleted", "author-deleted", "deleted", atMinute(0));
        markDeleted(deletedId);

        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(missingId, 5, 20, null, null))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(deletedId, 5, 20, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void shouldRejectOutOfRangeParamsAndNonEmptyCursor() {
        UUID rootId = UUID.randomUUID();
        createHumanPost(rootId, "req-root", "author-root", "root", atMinute(0));

        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(rootId, 0, 20, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(rootId, 100, 20, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(rootId, 5, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(rootId, 5, 10_000, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discussionTreeQueryService.getDiscussionTree(rootId, 5, 20, null, "cursor"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void createUserWithProfile(String userId, String username, String displayName) {
        neo4jClient.query("""
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  password_hash: 'hash',
                  roles: ['USER'],
                  status: 'ACTIVE',
                  created_at: $createdAt
                })
                """)
                .bind(username).to("username")
                .bind(userId).to("userId")
                .bind(atMinute(0)).to("createdAt")
                .run();
        neo4jClient.query("""
                MATCH (user:UserAccount {user_id: $userId})
                MERGE (profile:UserProfile {user_id: $userId})
                SET profile.display_name = $displayName,
                    profile.updated_at = $updatedAt
                MERGE (user)-[:HAS_PROFILE]->(profile)
                """)
                .bind(userId).to("userId")
                .bind(displayName).to("displayName")
                .bind(atMinute(0)).to("updatedAt")
                .run();
    }

    private void createHumanPost(UUID nodeId, String requestId, String authorId, String content, OffsetDateTime createdAt) {
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
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createAiConsensus(UUID nodeId, String summaryContent, String agentVersion, OffsetDateTime createdAt) {
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
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createResult(UUID nodeId, String content, String agentVersion, OffsetDateTime createdAt) {
        neo4jClient.query("""
                CREATE (:Result:GraphNode {
                  node_id: $nodeId,
                  content: $content,
                  agent_version: $agentVersion,
                  created_at: $createdAt
                })
                """)
                .bind(nodeId.toString()).to("nodeId")
                .bind(content).to("content")
                .bind(agentVersion).to("agentVersion")
                .bind(createdAt).to("createdAt")
                .run();
    }

    private void createContinuesFrom(UUID childId, UUID parentId) {
        createRelationship(childId, parentId, "CONTINUES_FROM");
    }

    private void createBranchedFrom(UUID childId, UUID parentId) {
        createRelationship(childId, parentId, "BRANCHED_FROM");
    }

    private void createMergedInto(UUID artifactId, UUID anchorId) {
        createRelationship(artifactId, anchorId, "MERGED_INTO");
    }

    private void createSynthesizedFrom(UUID consensusId, UUID sourceId) {
        createRelationship(consensusId, sourceId, "SYNTHESIZED_FROM");
    }

    private void createMaterializedFrom(UUID resultId, UUID sourceId) {
        createRelationship(resultId, sourceId, "MATERIALIZED_FROM");
    }

    private void createRelationship(UUID sourceId, UUID targetId, String relationshipType) {
        neo4jClient.query(String.format("""
                MATCH (source:GraphNode {node_id: $sourceId}), (target:GraphNode {node_id: $targetId})
                CREATE (source)-[:%s {created_at: $createdAt}]->(target)
                """, relationshipType))
                .bind(sourceId.toString()).to("sourceId")
                .bind(targetId.toString()).to("targetId")
                .bind(atMinute(0)).to("createdAt")
                .run();
    }

    private void markDeleted(UUID nodeId) {
        neo4jClient.query("""
                MATCH (n:GraphNode {node_id: $nodeId})
                SET n._deleted = true
                """)
                .bind(nodeId.toString()).to("nodeId")
                .run();
    }

    private static OffsetDateTime atMinute(int minute) {
        return OffsetDateTime.of(2026, 5, 20, 10, minute, 0, 0, ZoneOffset.UTC);
    }
}
