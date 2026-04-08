package com.rhizodelta.ai.routing.domain;

/**
 * 表示反思校验阶段的结果。
 *
 * <p>该对象用于描述模型自我审查后是否确认原决策、是否修正动作和置信度，
 * 以及最终给出的批评理由。
 */
public record ReflectionResult(
        boolean confirmed,
        String revisedAction,
        double revisedConfidence,
        String criticReason
) {
    /**
     * 创建反思结果并校验修正动作和置信度范围。
     */
    public ReflectionResult {
        if (revisedAction == null || revisedAction.isBlank()) {
            throw new IllegalArgumentException("revisedAction must not be blank");
        }
        if (criticReason == null) {
            throw new IllegalArgumentException("criticReason must not be null");
        }
        if (revisedConfidence < 0.0 || revisedConfidence > 1.0) {
            throw new IllegalArgumentException("revisedConfidence must be between 0.0 and 1.0");
        }
    }
}
