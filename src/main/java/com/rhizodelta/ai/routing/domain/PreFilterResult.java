package com.rhizodelta.ai.routing.domain;

/**
 * 表示规则预过滤阶段的结果。
 *
 * <p>该对象用于告诉后续工作流：当前应该采取什么初始动作，以及是否可以跳过 LLM 评估阶段。
 */
public record PreFilterResult(
        String action,
        String reason,
        boolean skipLlm
) {
    /**
     * 创建预过滤结果并校验动作字段。
     */
    public PreFilterResult {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}
