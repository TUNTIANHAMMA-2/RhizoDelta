package com.rhizodelta.consensus.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示分叉决策执行后的聚合结果。
 *
 * <p>该结果对象用于告诉调用方：一个分叉批次创建了哪些节点、成功创建了多少个分支，
 * 以及整体操作的状态。
 */
public record ForkDecisionResult(
        @JsonProperty("operation_id") String operation_id,
        @JsonProperty("node_ids") List<UUID> node_ids,
        @JsonProperty("status") String status,
        @JsonProperty("created_count") int created_count,
        @JsonProperty("total_count") int total_count
) {
    /**
     * 创建分叉结果并校验关键字段。
     *
     * <p>节点列表会被复制为不可变集合，避免调用方在结果返回后再篡改分叉结果视图。
     */
    public ForkDecisionResult {
        Objects.requireNonNull(operation_id, "operation_id must not be null");
        Objects.requireNonNull(node_ids, "node_ids must not be null");
        Objects.requireNonNull(status, "status must not be null");
        node_ids = List.copyOf(node_ids);
    }
}
