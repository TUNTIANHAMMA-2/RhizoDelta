package com.rhizodelta.ai.summary.domain;

/**
 * 表示一次摘要生成结果。
 *
 * <p>该对象除了摘要正文外，还记录了来源数量和实际使用的模型名称，
 * 便于前端展示和审计追踪。
 */
public record SummaryResult(
        String summary,
        int sourceCount,
        String modelUsed
) {
    /**
     * 创建摘要结果并校验摘要正文。
     */
    public SummaryResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }
}
