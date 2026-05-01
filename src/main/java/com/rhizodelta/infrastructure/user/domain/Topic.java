package com.rhizodelta.infrastructure.user.domain;

import java.time.Instant;

public final class Topic {
    private final String topicId;
    private final String name;
    private final String sourceType;
    private final Instant createdAt;

    public Topic(String topicId, String name, String sourceType, Instant createdAt) {
        this.topicId = topicId;
        this.name = name;
        this.sourceType = sourceType;
        this.createdAt = createdAt;
    }

    public String topicId() { return topicId; }
    public String name() { return name; }
    public String sourceType() { return sourceType; }
    public Instant createdAt() { return createdAt; }
}
