package com.rhizodelta.infrastructure.messaging.message;

public record PostEventMessage(
        String requestId,
        String authorId,
        String content,
        String targetNodeId,
        String eventId
) {
}
