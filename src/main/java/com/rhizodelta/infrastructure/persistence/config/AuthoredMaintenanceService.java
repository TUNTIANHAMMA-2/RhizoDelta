package com.rhizodelta.infrastructure.persistence.config;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AuthoredMaintenanceService {
    private final Neo4jClient neo4jClient;

    public AuthoredMaintenanceService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional(transactionManager = "transactionManager")
    public AuthoredBackfillResult runAuthoredBackfill() {
        long touchedCount = neo4jClient.query(DatabaseInitializer.authoredBackfillQuery())
                .fetch()
                .one()
                .map(record -> readLong(record.get("touched")))
                .orElse(0L);
        List<MissingAuthoredEdge> missingSamples = findMissingAuthoredEdges();
        return new AuthoredBackfillResult(touchedCount, missingSamples);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<MissingAuthoredEdge> findMissingAuthoredEdges() {
        return neo4jClient.query(DatabaseInitializer.authoredAuditQuery())
                .fetch()
                .all()
                .stream()
                .map(this::toMissingAuthoredEdge)
                .toList();
    }

    private MissingAuthoredEdge toMissingAuthoredEdge(Map<String, Object> record) {
        return new MissingAuthoredEdge(
                record.get("nodeId").toString(),
                record.get("authorId").toString()
        );
    }

    private long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    public record AuthoredBackfillResult(
            long touchedCount,
            List<MissingAuthoredEdge> missingSamples
    ) {
    }

    public record MissingAuthoredEdge(
            String nodeId,
            String authorId
    ) {
    }
}
