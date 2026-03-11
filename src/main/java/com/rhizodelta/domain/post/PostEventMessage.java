package com.rhizodelta.domain.post;

public record PostEventMessage(
        String requestId,
        String authorId,
        String content,
        String targetNodeId,
        String eventId
) {
}
