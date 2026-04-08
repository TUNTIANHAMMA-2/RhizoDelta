package com.rhizodelta.consensus.domain.decision;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;

import java.util.List;
import java.util.UUID;

/**
 * 表示一次共识合并决策命令。
 *
 * <p>该命令用于把若干来源帖子汇聚为一个共识节点，并挂接到指定的源节点上。
 * 它通常会驱动系统创建或追加 {@code AI_Consensus} 节点及其来源关系。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>{@code synthesized_from} 必须提供且不能为空，因为它决定了共识的来源链。</li>
 *   <li>{@code summary_content} 是共识节点的核心输出，不允许为空白。</li>
 *   <li>{@code operator_type}/{@code operator_id} 共同描述此次决策的实际执行者。</li>
 * </ul>
 */
public record MergeDecisionCommand(
        @JsonProperty("decision_id") String decision_id,
        @JsonProperty("request_id") String request_id,
        @JsonProperty("source_node_id") UUID source_node_id,
        @JsonProperty("agent_version") String agent_version,
        @JsonProperty("summary_content") String summary_content,
        @JsonProperty("synthesized_from") List<UUID> synthesized_from,
        @JsonProperty("operator_type") DecisionOperatorType operator_type,
        @JsonProperty("operator_id") String operator_id,
        @JsonProperty("reason") String reason
) {
    /**
     * 创建合并命令并校验核心输入。
     *
     * <p>该校验发生在模型层，确保服务层只处理结构完整且语义自洽的合并请求。
     */
    public MergeDecisionCommand {
        decision_id = DecisionCommandValidation.requireText(decision_id, "decision_id");
        request_id = DecisionCommandValidation.requireText(request_id, "request_id");
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        agent_version = DecisionCommandValidation.requireText(agent_version, "agent_version");
        summary_content = DecisionCommandValidation.requireText(summary_content, "summary_content");
        synthesized_from = DecisionCommandValidation.requireUuidList(synthesized_from);
        operator_type = DecisionCommandValidation.requireOperatorType(operator_type);
        operator_id = DecisionCommandValidation.requireText(operator_id, "operator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
    }
}
