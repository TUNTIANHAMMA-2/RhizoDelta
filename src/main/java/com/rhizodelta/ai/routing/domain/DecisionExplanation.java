package com.rhizodelta.ai.routing.domain;

/**
 * 表示路由决策的结构化解释。
 *
 * <p>该对象用于把最终动作、置信度、理由以及候选比较与反思总结打包成一个可序列化解释结果，
 * 供 SSE、日志或人工复核界面展示。
 */
public record DecisionExplanation(
        String action,
        double confidence,
        String reason,
        String candidateComparison,
        String reflectionSummary
) {
    /**
     * 创建结构化决策解释并校验核心字段。
     */
    public DecisionExplanation {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
