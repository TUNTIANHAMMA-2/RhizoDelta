package com.rhizodelta.ai.quality.domain;

import java.util.UUID;

/**
 * 表示一次内容质量评估请求。
 *
 * <p>该命令对象封装了质量评估所需的正文、上下文片段和图位置描述，
 * 供 {@link com.rhizodelta.ai.quality.service.QualityAgentService} 生成评分。
 */
public record QualityEvaluationCommand(
        UUID nodeId,
        String content,
        String contextSnippet,
        String positionInfo
) {
    /**
     * 创建评估命令并校验最小输入。
     *
     * <p>这里至少要求节点 ID 和正文存在，因为它们是质量评分的最小前提。
     */
    public QualityEvaluationCommand {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
