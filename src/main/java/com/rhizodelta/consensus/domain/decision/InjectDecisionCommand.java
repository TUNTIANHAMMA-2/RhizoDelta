package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.UUID;

/**
 * 表示一次注入决策命令。
 *
 * <p>该命令用于把一条新帖子以 {@code CONTINUES_FROM} 的形式接入既有节点，
 * 但不创建共识节点也不形成新的平行分支。
 *
 * <p><b>适用场景</b>：
 * <ul>
 *   <li>内容与现有讨论强相关，但尚不足以形成摘要共识。</li>
 *   <li>希望保留时间序列连续性，而不是做汇聚或分叉。</li>
 * </ul>
 */
public record InjectDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("content") String content,
        @JsonProperty("author_id") String author_id,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    /**
     * 创建注入命令并执行基础完整性校验。
     */
    public InjectDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        content = DecisionCommandValidation.requireText(content, "content");
        author_id = DecisionCommandValidation.requireText(author_id, "author_id");
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
