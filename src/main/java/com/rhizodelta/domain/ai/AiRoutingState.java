package com.rhizodelta.domain.ai;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AiRoutingState extends AgentState {
    public static final String REQUEST_ID = "requestId";
    public static final String EVENT_ID = "eventId";
    public static final String POST_NODE_ID = "postNodeId";
    public static final String TARGET_NODE_ID = "targetNodeId";
    public static final String ROUTING_ACTION = "routingAction";
    public static final String REVIEW_REASON = "reviewReason";
    public static final String EXECUTED_NODES = "executedNodes";
    private static final String DEFAULT_ACTION = "REVIEW";
    private static final String EMPTY_TEXT = "";

    public AiRoutingState(Map<String, Object> data) {
        super(data);
    }

    public String requestId() {
        return value(REQUEST_ID, EMPTY_TEXT);
    }

    public String eventId() {
        return value(EVENT_ID, EMPTY_TEXT);
    }

    public String postNodeId() {
        return value(POST_NODE_ID, EMPTY_TEXT);
    }

    public String targetNodeId() {
        return value(TARGET_NODE_ID, EMPTY_TEXT);
    }

    public String routingAction() {
        return value(ROUTING_ACTION, DEFAULT_ACTION);
    }

    public String reviewReason() {
        return value(REVIEW_REASON, EMPTY_TEXT);
    }

    @SuppressWarnings("unchecked")
    public List<String> executedNodes() {
        return value(EXECUTED_NODES, List::of);
    }

    public static Map<String, Channel<?>> channels() {
        Map<String, Channel<?>> channels = new HashMap<>();
        channels.put(REQUEST_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(EVENT_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(POST_NODE_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(TARGET_NODE_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(ROUTING_ACTION, Channels.base(() -> DEFAULT_ACTION));
        channels.put(REVIEW_REASON, Channels.base(() -> EMPTY_TEXT));
        channels.put(EXECUTED_NODES, Channels.appender(java.util.ArrayList::new));
        return Map.copyOf(channels);
    }
}
