package com.rhizodelta.consensus.domain.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 表示一条等待人工处理的复核任务。
 *
 * <p>该模型承载 AI 路由或决策流程中无法自动闭合的场景，
 * 用于把候选动作、草稿载荷和原因码交给人工管理员处理。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>{@code draftPayload} 保存待执行的原始草稿，供批准时重建正式命令。</li>
 *   <li>{@code reviewReasonCodes} 用于解释为什么系统没有自动提交决策。</li>
 *   <li>集合字段都会被复制为不可变对象，避免任务在内存中被外部篡改。</li>
 * </ul>
 */
public record ReviewTask(
        String reviewId,
        String requestId,
        String postNodeId,
        String workflowTraceId,
        Status status,
        String suggestedAction,
        List<String> candidateNodeIds,
        Map<String, Object> draftPayload,
        List<String> reviewReasonCodes,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
) {
    /**
     * 创建复核任务并归一化集合字段。
     *
     * <p>这里允许空集合输入，但会统一收敛为不可变空集合，降低上层判空成本。
     */
    public ReviewTask {
        candidateNodeIds = candidateNodeIds == null ? List.of() : List.copyOf(candidateNodeIds);
        draftPayload = draftPayload == null ? Map.of() : Map.copyOf(draftPayload);
        reviewReasonCodes = reviewReasonCodes == null ? List.of() : List.copyOf(reviewReasonCodes);
    }

    /**
     * 定义复核任务的生命周期状态。
     */
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        EXECUTION_FAILED
    }

    /**
     * 表示创建待复核任务所需的最小输入。
     *
     * <p>该命令对象通常由工作流或编排层生成，再交给复核任务服务持久化。
     */
    public record CreateReviewTaskCommand(
            String requestId,
            String postNodeId,
            String workflowTraceId,
            String suggestedAction,
            List<String> candidateNodeIds,
            Map<String, Object> draftPayload,
            List<String> reviewReasonCodes
    ) {
    }
}
