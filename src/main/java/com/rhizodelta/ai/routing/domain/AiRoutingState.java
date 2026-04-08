package com.rhizodelta.ai.routing.domain;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示 AI 路由工作流的运行时状态。
 *
 * <p>该对象基于 {@link AgentState} 封装了路由流程中各阶段共享的数据槽位，
 * 例如召回候选、路由动作、反思次数和结构化解释。
 *
 * <p><b>设计意图</b>：
 * <ul>
 *   <li>用统一的 channel key 避免工作流节点之间散落字符串常量。</li>
 *   <li>为缺省值提供稳定回退，避免节点执行时频繁判空。</li>
 *   <li>让 {@code ai.routing.service} 能以类型化方式读取工作流状态。</li>
 * </ul>
 */
public final class AiRoutingState extends AgentState {
    public static final String REQUEST_ID = "requestId";
    public static final String EVENT_ID = "eventId";
    public static final String POST_NODE_ID = "postNodeId";
    public static final String POST_CONTENT = "postContent";
    public static final String TARGET_NODE_ID = "targetNodeId";
    public static final String SOURCE_NODE_ID = "sourceNodeId";
    public static final String RECALL_CANDIDATE_NODE_IDS = "recallCandidateNodeIds";
    public static final String SELECTED_CANDIDATE_NODE_IDS = "selectedCandidateNodeIds";
    public static final String ROUTING_CONTEXT = "routingContext";
    public static final String WORKFLOW_STARTED_AT = "workflowStartedAt";
    public static final String ROUTING_ACTION = "routingAction";
    public static final String REVIEW_REASON = "reviewReason";
    public static final String EXECUTED_NODES = "executedNodes";
    public static final String RULE_DECISION = "ruleDecision";
    public static final String SKIP_LLM = "skipLlm";
    public static final String TOP_SCORE = "topScore";
    public static final String REFLECTION_COUNT = "reflectionCount";
    public static final String DECISION_EXPLANATION = "decisionExplanation";
    public static final String INITIAL_ACTION = "initialAction";
    public static final String INITIAL_CONFIDENCE = "initialConfidence";
    public static final String CRITIC_FEEDBACK = "criticFeedback";
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

    public String postContent() {
        return value(POST_CONTENT, EMPTY_TEXT);
    }

    public String targetNodeId() {
        return value(TARGET_NODE_ID, EMPTY_TEXT);
    }

    public String sourceNodeId() {
        return value(SOURCE_NODE_ID, EMPTY_TEXT);
    }

    @SuppressWarnings("unchecked")
    public List<String> recallCandidateNodeIds() {
        return value(RECALL_CANDIDATE_NODE_IDS, List::of);
    }

    @SuppressWarnings("unchecked")
    public List<String> selectedCandidateNodeIds() {
        return value(SELECTED_CANDIDATE_NODE_IDS, List::of);
    }

    public String routingContext() {
        return value(ROUTING_CONTEXT, EMPTY_TEXT);
    }

    public Instant workflowStartedAt() {
        return value(WORKFLOW_STARTED_AT, Instant.EPOCH);
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

    public String ruleDecision() {
        return value(RULE_DECISION, EMPTY_TEXT);
    }

    public boolean skipLlm() {
        return value(SKIP_LLM, false);
    }

    public double topScore() {
        return value(TOP_SCORE, 0.0d);
    }

    public int reflectionCount() {
        return value(REFLECTION_COUNT, 0);
    }

    public String decisionExplanation() {
        return value(DECISION_EXPLANATION, EMPTY_TEXT);
    }

    public String initialAction() {
        return value(INITIAL_ACTION, EMPTY_TEXT);
    }

    public double initialConfidence() {
        return value(INITIAL_CONFIDENCE, 0.0d);
    }

    public String criticFeedback() {
        return value(CRITIC_FEEDBACK, EMPTY_TEXT);
    }

    /**
     * 定义工作流状态通道及其默认值。
     *
     * <p>该方法是路由状态图编译时的关键入口，决定每个字段如何初始化和追加。
     */
    public static Map<String, Channel<?>> channels() {
        Map<String, Channel<?>> channels = new HashMap<>();
        channels.put(REQUEST_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(EVENT_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(POST_NODE_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(POST_CONTENT, Channels.base(() -> EMPTY_TEXT));
        channels.put(TARGET_NODE_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(SOURCE_NODE_ID, Channels.base(() -> EMPTY_TEXT));
        channels.put(RECALL_CANDIDATE_NODE_IDS, Channels.base(() -> List.<String>of()));
        channels.put(SELECTED_CANDIDATE_NODE_IDS, Channels.base(() -> List.<String>of()));
        channels.put(ROUTING_CONTEXT, Channels.base(() -> EMPTY_TEXT));
        channels.put(WORKFLOW_STARTED_AT, Channels.base(() -> Instant.EPOCH));
        channels.put(ROUTING_ACTION, Channels.base(() -> DEFAULT_ACTION));
        channels.put(REVIEW_REASON, Channels.base(() -> EMPTY_TEXT));
        channels.put(EXECUTED_NODES, Channels.appender(java.util.ArrayList::new));
        channels.put(RULE_DECISION, Channels.base(() -> EMPTY_TEXT));
        channels.put(SKIP_LLM, Channels.base(() -> false));
        channels.put(TOP_SCORE, Channels.base(() -> 0.0d));
        channels.put(REFLECTION_COUNT, Channels.base(() -> 0));
        channels.put(DECISION_EXPLANATION, Channels.base(() -> EMPTY_TEXT));
        channels.put(INITIAL_ACTION, Channels.base(() -> EMPTY_TEXT));
        channels.put(INITIAL_CONFIDENCE, Channels.base(() -> 0.0d));
        channels.put(CRITIC_FEEDBACK, Channels.base(() -> EMPTY_TEXT));
        return Map.copyOf(channels);
    }
}
