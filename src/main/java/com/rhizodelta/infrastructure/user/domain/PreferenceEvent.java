package com.rhizodelta.infrastructure.user.domain;

import java.time.Instant;

public final class PreferenceEvent {
    private final String eventId;
    private final PreferenceEventType type;
    private final double weight;
    private final Instant at;
    private final String sourceNodeId;

    public PreferenceEvent(String eventId, PreferenceEventType type, double weight, Instant at, String sourceNodeId) {
        this.eventId = eventId;
        this.type = type;
        this.weight = weight;
        this.at = at;
        this.sourceNodeId = sourceNodeId;
    }

    public String eventId() { return eventId; }
    public PreferenceEventType type() { return type; }
    public double weight() { return weight; }
    public Instant at() { return at; }
    public String sourceNodeId() { return sourceNodeId; }
}
