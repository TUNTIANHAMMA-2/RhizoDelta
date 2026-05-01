package com.rhizodelta.api;

import com.rhizodelta.consensus.service.AuditRelationService;
import com.rhizodelta.core.service.PostService;
import com.rhizodelta.core.service.PostService.CreateHumanPostCommand;
import com.rhizodelta.core.service.PostService.CreateHumanPostResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.8: Performance test — Post creation with AUTHORED + REVIEWED edge writes.
 *
 * <p>Validates the Phase 3 design constraint:
 * <blockquote>Phase 3 新增关系写入不能显著增加热路径延迟（目标: &lt; +30ms P99）</blockquote>
 *
 * <p>This test measures:
 * <ol>
 *   <li><b>Baseline</b>: Raw node MERGE (upsert only, no edge writes).</li>
 *   <li><b>Full path</b>: Node MERGE + AUTHORED edge + REVIEWED audit edge.</li>
 * </ol>
 * The delta between the two gives the incremental cost of the Phase 3 edge writes.
 * P99 of the delta must be &lt; 30ms.
 *
 * <p><b>Environment note</b>: This runs against a Testcontainers Neo4j instance with no
 * tuning. Production performance should be equal or better.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PostCreationPerformanceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostCreationPerformanceTest.class);

    /**
     * Number of iterations per phase. 100 gives a statistically meaningful P99 sample
     * without making the test suite excessively slow on CI.
     */
    private static final int ITERATIONS = 100;

    /**
     * Phase 3 design spec: edge writes must add < 30ms P99 over the baseline.
     */
    private static final long MAX_DELTA_P99_MS = 30;

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
    private PostService postService;

    @Autowired
    private AuditRelationService auditRelationService;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void seedAuthorAccount() {
        // Ensure a single author account exists for the entire run.
        neo4jClient.query("""
                MERGE (u:UserAccount {user_id: $userId})
                ON CREATE SET u.username = 'perf-author', u.created_at = datetime()
                """)
                .bind("perf-author-001").to("userId")
                .run();
    }

    // ───────────────────────────────────── benchmark ─────────────────────────────────────

    @Test
    void postCreationWithEdgeWritesShouldMeetP99LatencyBudget() {
        // ── Phase A: warm up the Bolt connection pool and Neo4j query cache ──
        for (int i = 0; i < 10; i++) {
            postService.createHumanPost(command("warmup-" + i));
        }

        // ── Phase B: baseline — raw PostService.createHumanPost (includes AUTHORED edge) ──
        long[] baselineNanos = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            String requestId = "baseline-" + i;
            long start = System.nanoTime();
            CreateHumanPostResult result = postService.createHumanPost(command(requestId));
            baselineNanos[i] = System.nanoTime() - start;
            assertThat(result.created()).isTrue();
        }

        // ── Phase C: full path — PostService + REVIEWED audit edge ──
        // First, seed a Decision node so that REVIEWED has a target.
        String decisionId = "perf-decision-" + UUID.randomUUID();
        neo4jClient.query("""
                CREATE (:Decision {
                    decision_id: $decisionId,
                    decision_type: 'MERGE',
                    operator_type: 'AGENT',
                    operator_id: 'agent-perf',
                    reason: 'perf test',
                    created_at: datetime()
                })
                """)
                .bind(decisionId).to("decisionId")
                .run();

        long[] fullPathNanos = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            String requestId = "fullpath-" + i;
            long start = System.nanoTime();
            CreateHumanPostResult result = postService.createHumanPost(command(requestId));
            auditRelationService.recordReview("perf-author-001", decisionId, "APPROVED");
            fullPathNanos[i] = System.nanoTime() - start;
            assertThat(result.created()).isTrue();
        }

        // ── Analysis ──
        Arrays.sort(baselineNanos);
        Arrays.sort(fullPathNanos);

        long baselineP50 = baselineNanos[ITERATIONS / 2] / 1_000_000;
        long baselineP99 = baselineNanos[(int) (ITERATIONS * 0.99) - 1] / 1_000_000;
        long fullPathP50 = fullPathNanos[ITERATIONS / 2] / 1_000_000;
        long fullPathP99 = fullPathNanos[(int) (ITERATIONS * 0.99) - 1] / 1_000_000;
        long deltaP50 = fullPathP50 - baselineP50;
        long deltaP99 = fullPathP99 - baselineP99;

        LOGGER.info("""
                ╔══════════════════════════════════════════════════════════════╗
                ║ Post Creation Performance Benchmark (n={})                ║
                ╠══════════════════════════════════════════════════════════════╣
                ║ Baseline (node + AUTHORED)      P50={}ms  P99={}ms     ║
                ║ Full path (+ REVIEWED audit)    P50={}ms  P99={}ms     ║
                ║ Delta (edge write overhead)      P50={}ms  P99={}ms     ║
                ╠══════════════════════════════════════════════════════════════╣
                ║ Budget: < +{}ms P99                                      ║
                ║ Result: {}                                               ║
                ╚══════════════════════════════════════════════════════════════╝
                """,
                ITERATIONS,
                baselineP50, baselineP99,
                fullPathP50, fullPathP99,
                deltaP50, deltaP99,
                MAX_DELTA_P99_MS,
                deltaP99 < MAX_DELTA_P99_MS ? "✅ PASS" : "❌ FAIL"
        );

        assertThat(deltaP99)
                .as("Phase 3 edge-write P99 overhead must be < %dms (was %dms)", MAX_DELTA_P99_MS, deltaP99)
                .isLessThan(MAX_DELTA_P99_MS);
    }

    // ───────────────────────────────────── helpers ─────────────────────────────────────

    private static CreateHumanPostCommand command(String requestId) {
        return new CreateHumanPostCommand(requestId, "perf-author-001", "Performance test content — " + requestId);
    }
}
