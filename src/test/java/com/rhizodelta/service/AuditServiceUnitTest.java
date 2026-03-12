package com.rhizodelta.service;

import com.rhizodelta.domain.association.AssociationInfo;
import com.rhizodelta.domain.association.AssociationType;
import com.rhizodelta.domain.association.CreateAssociationCommand;
import com.rhizodelta.domain.audit.AuditDetail;
import com.rhizodelta.domain.audit.AuditListResponse;
import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.DecisionType;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.decision.RollbackResult;
import com.rhizodelta.exception.DagIntegrityViolationException;
import com.rhizodelta.exception.RollbackBlockedException;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceUnitTest {
    @Test
    void listDecisionsShouldUseDefaultLimitAndReturnNextCursor() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().all()).thenReturn(buildRecords(21));
        AuditService auditService = new AuditService(neo4jClient);

        AuditListResponse response = auditService.listDecisions(null, null, null, null, null, null);

        assertThat(response.records()).hasSize(20);
        assertThat(response.next_cursor()).isNotBlank();
        verify(neo4jClient.query(anyString())).bindAll(argThat((Map<String, Object> params) ->
                Integer.valueOf(21).equals(params.get("fetchSize"))
                        && params.get("decisionType") == null
                        && params.get("afterCreatedAt") == null
                        && params.get("afterDecisionId") == null
        ));
    }

    @Test
    void listDecisionsShouldApplyFiltersAndCursor() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        Instant afterTime = Instant.parse("2026-02-01T00:00:00Z");
        String cursor = AuditService.encodeCursor(afterTime, "decision-001");
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().all()).thenReturn(List.of(record(1)));
        AuditService auditService = new AuditService(neo4jClient);

        AuditListResponse response = auditService.listDecisions(
                "merge",
                "agent-42",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-01T00:00:00Z"),
                cursor,
                10
        );

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).decision_type()).isEqualTo(DecisionType.MERGE);
        assertThat(response.next_cursor()).isNull();
        verify(neo4jClient.query(anyString())).bindAll(argThat((Map<String, Object> params) ->
                Integer.valueOf(11).equals(params.get("fetchSize"))
                        && "MERGE".equals(params.get("decisionType"))
                        && "agent-42".equals(params.get("operatorId"))
                        && OffsetDateTime.ofInstant(afterTime, java.time.ZoneOffset.UTC).equals(params.get("afterCreatedAt"))
                        && "decision-001".equals(params.get("afterDecisionId"))
        ));
    }

    @Test
    void listDecisionsShouldCapLimitToMax() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().all()).thenReturn(List.of());
        AuditService auditService = new AuditService(neo4jClient);

        AuditListResponse response = auditService.listDecisions(null, null, null, null, null, 500);

        assertThat(response.records()).isEmpty();
        verify(neo4jClient.query(anyString())).bindAll(argThat((Map<String, Object> params) ->
                Integer.valueOf(101).equals(params.get("fetchSize"))
        ));
    }

    @Test
    void listDecisionsShouldRejectInvalidType() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AuditService auditService = new AuditService(neo4jClient);

        assertThatThrownBy(() -> auditService.listDecisions("INVALID", null, null, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Allowed values: MERGE, BRANCH");
    }

    @Test
    void listDecisionsShouldBindSinceAndUntilRange() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        Instant until = Instant.parse("2026-01-02T00:00:00Z");
        when(neo4jClient.query(anyString()).bindAll(anyMap()).fetch().all()).thenReturn(List.of(record(1)));
        AuditService auditService = new AuditService(neo4jClient);

        AuditListResponse response = auditService.listDecisions(null, null, since, until, null, 5);

        assertThat(response.records()).hasSize(1);
        verify(neo4jClient.query(anyString())).bindAll(argThat((Map<String, Object> params) ->
                OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC).equals(params.get("since"))
                        && OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC).equals(params.get("until"))
        ));
    }

    @Test
    void listDecisionsShouldRejectInvalidCursor() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        AuditService auditService = new AuditService(neo4jClient);

        assertThatThrownBy(() -> auditService.listDecisions(null, null, null, null, "not-a-cursor", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cursor");
    }

    @Test
    void cursorEncodeDecodeShouldRoundTrip() {
        Instant createdAt = Instant.parse("2026-02-01T08:10:11Z");
        String decisionId = "decision-round-trip-001";

        String cursor = AuditService.encodeCursor(createdAt, decisionId);
        AuditService.Cursor decoded = AuditService.decodeCursor(cursor);

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.decisionId()).isEqualTo(decisionId);
    }

    @Test
    void getDecisionDetailShouldReturnMergeDetailWithSynthesizedFrom() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID contributorA = UUID.randomUUID();
        UUID contributorB = UUID.randomUUID();
        when(neo4jClient.query(anyString()).bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(Map.of(
                        "decisionId", "decision-merge",
                        "decisionType", "MERGE",
                        "nodeId", UUID.randomUUID().toString(),
                        "sourceNodeId", UUID.randomUUID().toString(),
                        "operatorType", "AGENT",
                        "operatorId", "agent-42",
                        "reason", "merge",
                        "createdAt", Instant.parse("2026-02-01T00:00:00Z"),
                        "synthesizedFrom", List.of(contributorA.toString(), contributorB.toString())
                )));
        AuditService auditService = new AuditService(neo4jClient);

        AuditDetail detail = auditService.getDecisionDetail("decision-merge");

        assertThat(detail.decision_type()).isEqualTo(DecisionType.MERGE);
        assertThat(detail.synthesized_from()).containsExactly(contributorA, contributorB);
    }

    @Test
    void getDecisionDetailShouldReturnBranchDetailWithEmptySynthesizedFrom() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.of(Map.of(
                        "decisionId", "decision-branch",
                        "decisionType", "BRANCH",
                        "nodeId", UUID.randomUUID(),
                        "sourceNodeId", UUID.randomUUID(),
                        "operatorType", "HUMAN",
                        "operatorId", "human-11",
                        "reason", "branch",
                        "createdAt", Instant.parse("2026-02-01T00:00:00Z"),
                        "synthesizedFrom", List.of()
                )));
        AuditService auditService = new AuditService(neo4jClient);

        AuditDetail detail = auditService.getDecisionDetail("decision-branch");

        assertThat(detail.decision_type()).isEqualTo(DecisionType.BRANCH);
        assertThat(detail.synthesized_from()).isEmpty();
    }

    @Test
    void getDecisionDetailShouldThrowWhenDecisionNotFound() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(anyString()).to(anyString()).fetch().one())
                .thenReturn(Optional.empty());
        AuditService auditService = new AuditService(neo4jClient);

        assertThatThrownBy(() -> auditService.getDecisionDetail("missing-decision"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("decision not found");
    }

    private static List<Map<String, Object>> buildRecords(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(AuditServiceUnitTest::record)
                .toList();
    }

    private static Map<String, Object> record(int index) {
        return Map.of(
                "decisionId", "decision-" + index,
                "decisionType", "MERGE",
                "nodeId", UUID.randomUUID().toString(),
                "sourceNodeId", UUID.randomUUID().toString(),
                "operatorType", "AGENT",
                "operatorId", "agent-" + index,
                "reason", "reason-" + index,
                "createdAt", Instant.parse("2026-02-01T00:00:00Z").minusSeconds(index)
        );
    }
}
