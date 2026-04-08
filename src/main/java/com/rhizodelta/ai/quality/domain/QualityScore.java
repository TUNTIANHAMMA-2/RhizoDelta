package com.rhizodelta.ai.quality.domain;

/**
 * 表示一次质量评估的结构化结果。
 *
 * <p>该对象同时保留四个维度分和综合分，便于后续查询、展示和排序使用。
 */
public record QualityScore(
        double relevance,
        double density,
        double argumentation,
        double communityValue,
        double overall,
        String reason
) {
    /**
     * 创建质量评分结果并校验解释文本。
     */
    public QualityScore {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    /**
     * 按系统约定的权重计算综合分。
     *
     * <p>该静态方法把评分权重集中在模型对象中，避免不同调用方各自复制计算公式。
     */
    public static double computeOverall(double relevance, double density, double argumentation, double communityValue) {
        return relevance * 0.25 + density * 0.25 + argumentation * 0.3 + communityValue * 0.2;
    }
}
