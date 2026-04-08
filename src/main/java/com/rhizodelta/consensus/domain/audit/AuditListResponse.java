package com.rhizodelta.consensus.domain.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * 表示审计列表分页响应。
 *
 * <p>该对象把当前页的 {@link AuditRecord} 集合与下一页游标绑定在一起，
 * 供审计查询接口稳定返回分页结果。
 */
public record AuditListResponse(
        @JsonProperty("records") List<AuditRecord> records,
        @JsonProperty("next_cursor") String next_cursor
) {
    /**
     * 创建分页响应并校验记录集合。
     *
     * <p>这里会复制记录列表，避免调用方在响应对象创建后再修改底层集合。
     */
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
