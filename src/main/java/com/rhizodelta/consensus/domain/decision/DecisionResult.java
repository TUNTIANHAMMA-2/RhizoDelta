package com.rhizodelta.consensus.domain.decision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.UUID;

/**
 * 表示一条决策提交后的标准结果。
 *
 * <p>该对象用于向 API 层或调用方确认：哪条决策已被接受、作用到哪个节点，以及当前处于什么状态。
 */
public record DecisionResult(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("node_id") UUID node_id,
        @JsonProperty("status") String status
) {
    /**
     * 创建决策结果并校验关键字段。
     *
     * <p>这里要求所有核心标识非空，以确保返回值可直接用于审计、日志和后续异步链路追踪。
     */
    public DecisionResult {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        node_id = DecisionCommandValidation.requireUuid(node_id, "node_id");
        status = DecisionCommandValidation.requireText(status, "status");
    }
}
