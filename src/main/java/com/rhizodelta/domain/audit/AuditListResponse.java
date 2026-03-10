package com.rhizodelta.domain.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record AuditListResponse(
        @JsonProperty("records") List<AuditRecord> records,
        @JsonProperty("next_cursor") String next_cursor
) {
    public AuditListResponse {
        records = validateRecords(records);
    }

    private static List<AuditRecord> validateRecords(List<AuditRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("records must not be null");
        }
        if (records.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("records must not contain null");
        }
        return List.copyOf(records);
    }
}
