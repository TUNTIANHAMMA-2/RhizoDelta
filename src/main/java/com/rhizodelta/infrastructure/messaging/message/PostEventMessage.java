package com.rhizodelta.infrastructure.messaging.message;

/**
 * 表示帖子提交后的异步事件消息。
 *
 * <p>该消息是帖子从同步 HTTP 层流入异步消费链路的载荷，
 * 会被消费者用于执行落库、embedding、质量评估和 AI 路由。
 */
public record PostEventMessage(
        String requestId,
        String authorId,
        String content,
        String targetNodeId,
        String eventId
) {
}
