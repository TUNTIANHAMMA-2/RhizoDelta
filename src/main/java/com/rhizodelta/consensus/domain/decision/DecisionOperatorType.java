package com.rhizodelta.consensus.domain.decision;

/**
 * 定义决策的操作者类型。
 *
 * <p>该枚举用于区分一条共识决策最终是由自动代理还是人工用户触发，
 * 是审计、权限控制和后续解释链路的重要维度。
 */
public enum DecisionOperatorType {
    AGENT,
    HUMAN
}
