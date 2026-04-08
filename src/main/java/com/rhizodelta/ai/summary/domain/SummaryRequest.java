package com.rhizodelta.ai.summary.domain;

import java.util.List;
import java.util.UUID;

/**
 * 表示一次摘要生成请求。
 *
 * <p>该对象封装了目标节点、来源文本集合以及可选的既有摘要，
 * 供摘要服务构造模型提示词时使用。
 */
public record SummaryRequest(
        UUID nodeId,
        List<String> sourceContents,
        String existingSummary
) {
    /**
     * 创建摘要请求并校验最小输入。
     *
     * <p>这里强制要求至少存在一个来源文本，否则摘要生成没有业务意义。
     */
    public SummaryRequest {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (sourceContents == null || sourceContents.isEmpty()) {
            throw new IllegalArgumentException("sourceContents must not be empty");
        }
    }
}
