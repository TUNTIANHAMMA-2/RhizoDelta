package com.rhizodelta.service;

import com.rhizodelta.domain.DecisionCommandValidation;
import com.rhizodelta.domain.audit.AuditDetail;
import com.rhizodelta.domain.audit.AuditListResponse;
import com.rhizodelta.domain.audit.AuditRecord;
import com.rhizodelta.domain.decision.DecisionOperatorType;
import com.rhizodelta.domain.decision.DecisionType;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditService {
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private static final String LIST_DECISIONS_QUERY = """
            MATCH (decision:GraphNode)-[rel:MERGED_INTO|BRANCHED_FROM|CONTINUES_FROM|CONVERGED_FROM|MATERIALIZED_FROM|CROSS_SYNTHESIZED_FROM]->(source:GraphNode)
            WITH decision, source, rel,
                 CASE type(rel)
                   WHEN 'MERGED_INTO' THEN 'MERGE'
                   WHEN 'BRANCHED_FROM' THEN 'BRANCH'
                   WHEN 'CONTINUES_FROM' THEN 'INJECT'
                   WHEN 'CONVERGED_FROM' THEN 'JOIN'
                   WHEN 'MATERIALIZED_FROM' THEN 'MATERIALIZE'
                   WHEN 'CROSS_SYNTHESIZED_FROM' THEN 'CROSS_SYNTH'
                 END AS decisionType,
                 coalesce(rel.decision_id, decision.decision_id) AS decisionId,
                 rel.created_at AS createdAt
            WHERE decisionId IS NOT NULL
              AND NOT coalesce(decision._deleted, false)
              AND NOT coalesce(source._deleted, false)
              AND ($decisionType IS NULL OR decisionType = $decisionType)
              AND ($operatorId IS NULL OR rel.operator_id = $operatorId)
              AND ($since IS NULL OR createdAt >= $since)
              AND ($until IS NULL OR createdAt < $until)
              AND (
                    $afterCreatedAt IS NULL
                    OR createdAt < $afterCreatedAt
                    OR (createdAt = $afterCreatedAt AND decisionId < $afterDecisionId)
                  )
            RETURN decisionId AS decisionId,
                   decisionType AS decisionType,
                   decision.node_id AS nodeId,
                   source.node_id AS sourceNodeId,
                   rel.operator_type AS operatorType,
                   rel.operator_id AS operatorId,
                   rel.reason AS reason,
                   createdAt AS createdAt
            ORDER BY createdAt DESC, decisionId DESC
            LIMIT $fetchSize
            """;

    private static final String GET_DECISION_DETAIL_QUERY = """
            MATCH (decision:GraphNode {decision_id: $decisionId})-[rel:MERGED_INTO|BRANCHED_FROM|CONTINUES_FROM|CONVERGED_FROM|MATERIALIZED_FROM|CROSS_SYNTHESIZED_FROM]->(source:GraphNode)
            WHERE NOT coalesce(decision._deleted, false)
              AND NOT coalesce(source._deleted, false)
            OPTIONAL MATCH (decision)-[:SYNTHESIZED_FROM]->(contributor:Human_Post)
            WITH decision, source, rel, [id IN collect(DISTINCT contributor.node_id) WHERE id IS NOT NULL] AS synthesizedFrom
            RETURN coalesce(rel.decision_id, decision.decision_id) AS decisionId,
                   CASE type(rel)
                     WHEN 'MERGED_INTO' THEN 'MERGE'
                     WHEN 'BRANCHED_FROM' THEN 'BRANCH'
                     WHEN 'CONTINUES_FROM' THEN 'INJECT'
                     WHEN 'CONVERGED_FROM' THEN 'JOIN'
                     WHEN 'MATERIALIZED_FROM' THEN 'MATERIALIZE'
                     WHEN 'CROSS_SYNTHESIZED_FROM' THEN 'CROSS_SYNTH'
                   END AS decisionType,
                   decision.node_id AS nodeId,
                   source.node_id AS sourceNodeId,
                   rel.operator_type AS operatorType,
                   rel.operator_id AS operatorId,
                   rel.reason AS reason,
                   rel.created_at AS createdAt,
                   synthesizedFrom AS synthesizedFrom
            """;

    private static final String CURSOR_DELIMITER = "|";
    private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();

    private final Neo4jClient neo4jClient;

    public AuditService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public AuditListResponse listDecisions(
            String type,
            String operatorId,
            Instant since,
            Instant until,
            String after,
            Integer limit
    ) {
        DecisionType decisionType = parseDecisionType(type);
        validateTimeRange(since, until);
        int pageSize = resolvePageSize(limit);
        Cursor cursor = resolveCursor(after);

        Map<String, Object> params = buildListParams(decisionType, operatorId, since, until, cursor, pageSize + 1);
        List<AuditRecord> records = neo4jClient.query(LIST_DECISIONS_QUERY)
                .bindAll(params)
                .fetch()
                .all()
                .stream()
                .map(AuditService::toAuditRecord)
                .toList();
        return toPagedResponse(records, pageSize);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public AuditDetail getDecisionDetail(String decisionId) {
        String validatedDecisionId = DecisionCommandValidation.requireText(decisionId, "decision_id");
        Map<String, Object> record = neo4jClient.query(GET_DECISION_DETAIL_QUERY)
                .bind(validatedDecisionId)
                .to("decisionId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("decision not found: " + validatedDecisionId));
        return toAuditDetail(record);
    }

    private static Map<String, Object> buildListParams(
            DecisionType decisionType,
            String operatorId,
            Instant since,
            Instant until,
            Cursor cursor,
            int fetchSize
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("decisionType", decisionType == null ? null : decisionType.name());
        params.put("operatorId", operatorId);
        params.put("since", since == null ? null : OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC));
        params.put("until", until == null ? null : OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC));
        params.put("afterCreatedAt", cursor == null ? null : OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
        params.put("afterDecisionId", cursor == null ? null : cursor.decisionId());
        params.put("fetchSize", fetchSize);
        return params;
    }

    private static AuditRecord toAuditRecord(Map<String, Object> row) {
        return new AuditRecord(
                requireText(row, "decisionId"),
                parseDecisionType(requireText(row, "decisionType")),
                toUuid(row.get("nodeId"), "nodeId"),
                toUuid(row.get("sourceNodeId"), "sourceNodeId"),
                parseOperatorType(row.get("operatorType")),
                requireText(row, "operatorId"),
                requireText(row, "reason"),
                toInstant(row.get("createdAt"))
        );
    }

    private static AuditDetail toAuditDetail(Map<String, Object> row) {
        return new AuditDetail(
                requireText(row, "decisionId"),
                parseDecisionType(requireText(row, "decisionType")),
                toUuid(row.get("nodeId"), "nodeId"),
                toUuid(row.get("sourceNodeId"), "sourceNodeId"),
                parseOperatorType(row.get("operatorType")),
                requireText(row, "operatorId"),
                requireText(row, "reason"),
                toInstant(row.get("createdAt")),
                toUuidList(row.get("synthesizedFrom"), "synthesizedFrom")
        );
    }

    private static AuditListResponse toPagedResponse(List<AuditRecord> records, int pageSize) {
        boolean hasMore = records.size() > pageSize;
        List<AuditRecord> pageRecords = hasMore
                ? new ArrayList<>(records.subList(0, pageSize))
                : new ArrayList<>(records);
        String nextCursor = null;
        if (hasMore && !pageRecords.isEmpty()) {
            AuditRecord last = pageRecords.get(pageRecords.size() - 1);
            nextCursor = encodeCursor(last.created_at(), last.decision_id());
        }
        return new AuditListResponse(List.copyOf(pageRecords), nextCursor);
    }

    static String encodeCursor(Instant createdAt, String decisionId) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        String validatedDecisionId = DecisionCommandValidation.requireText(decisionId, "decision_id");
        String payload = createdAt + CURSOR_DELIMITER + validatedDecisionId;
        return CURSOR_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String cursor) {
        String validatedCursor = DecisionCommandValidation.requireText(cursor, "after");
        try {
            String payload = new String(CURSOR_DECODER.decode(validatedCursor), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return new Cursor(Instant.parse(parts[0]), DecisionCommandValidation.requireText(parts[1], "decision_id"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid cursor", exception);
        }
    }

    private static Cursor resolveCursor(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }
        return decodeCursor(after);
    }

    private static DecisionType parseDecisionType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        try {
            return DecisionType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid decision type. Allowed values: MERGE, BRANCH, INJECT, MATERIALIZE, FORK, CROSS_SYNTH, JOIN", exception);
        }
    }

    private static void validateTimeRange(Instant since, Instant until) {
        if (since != null && until != null && !since.isBefore(until)) {
            throw new IllegalArgumentException("since must be before until");
        }
    }

    private static int resolvePageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String requireText(Map<String, Object> row, String key) {
        return DecisionCommandValidation.requireText((String) row.get(key), key);
    }

    private static DecisionOperatorType parseOperatorType(Object value) {
        String text = DecisionCommandValidation.requireText((String) value, "operatorType");
        return DecisionOperatorType.valueOf(text);
    }

    private static UUID toUuid(Object value, String fieldName) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            return UUID.fromString(text);
        }
        throw new IllegalStateException(fieldName + " must be uuid");
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        throw new IllegalStateException("createdAt must be an instant-compatible value");
    }

    private static List<UUID> toUuidList(Object value, String fieldName) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(fieldName + " must be a list");
        }
        List<UUID> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            result.add(toUuid(entry, fieldName));
        }
        return List.copyOf(result);
    }

    record Cursor(Instant createdAt, String decisionId) {
    }
}
