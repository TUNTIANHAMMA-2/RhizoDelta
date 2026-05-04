package com.rhizodelta.ai.routing.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tolerant JSON extractor in {@link AiRoutingEvaluatorService}.
 *
 * <p>Real-world failure mode: small open-weight chat models (Qwen2.5-7B, etc.) ignore
 * the "do not wrap in markdown" instruction and emit fenced JSON, prose preambles,
 * or chain-of-thought leakage. {@link AiRoutingEvaluatorService#extractJsonObject}
 * must return a parseable JSON substring for each of these patterns; otherwise
 * {@code parseDecision} throws and the routing pipeline aborts.
 */
class AiRoutingEvaluatorJsonExtractorUnitTest {

    @Test
    void cleanJsonPassesThrough() {
        String raw = "{\"action\":\"BRANCH\",\"confidence\":0.7,\"reason\":\"new angle\"}";
        assertThat(AiRoutingEvaluatorService.extractJsonObject(raw)).isEqualTo(raw);
    }

    @Test
    void stripsJsonMarkdownFence() {
        String raw = """
                ```json
                {"action":"MERGE","confidence":0.9,"reason":"identical idea"}
                ```
                """;
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).startsWith("{").endsWith("}");
        assertThat(extracted).contains("MERGE");
    }

    @Test
    void stripsBareTripleBacktickFence() {
        String raw = """
                ```
                {"action":"REVIEW","confidence":0.4,"reason":"ambiguous"}
                ```""";
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).startsWith("{").endsWith("}");
        assertThat(extracted).contains("REVIEW");
    }

    @Test
    void extractsJsonAfterPreamble() {
        String raw = "Here is my decision:\n"
                + "{\"action\":\"BRANCH\",\"confidence\":0.6,\"reason\":\"diverges\"}";
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).startsWith("{").endsWith("}");
        assertThat(extracted).contains("BRANCH");
    }

    @Test
    void extractsJsonWithTrailingCommentary() {
        String raw = "{\"action\":\"REVIEW\",\"confidence\":0.5,\"reason\":\"unclear\"} "
                + "(let me know if you want me to revisit this)";
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted)
                .isEqualTo("{\"action\":\"REVIEW\",\"confidence\":0.5,\"reason\":\"unclear\"}");
    }

    @Test
    void respectsBracesNestedInsideStringLiterals() {
        // The reason field contains a literal "{x}" — naive depth counting on every
        // brace would close the object early and yield invalid JSON.
        String raw = "{\"action\":\"BRANCH\",\"confidence\":0.6,\"reason\":\"branch from {x}\"}";
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).isEqualTo(raw);
    }

    @Test
    void handlesEscapedQuotesInsideStrings() {
        String raw = "{\"action\":\"MERGE\",\"confidence\":0.85,\"reason\":\"\\\"identical\\\" topic\"}";
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).isEqualTo(raw);
    }

    @Test
    void chainOfThoughtLeakBeforeJson() {
        String raw = """
                Let me think step by step.
                The post talks about pruning strategy.
                The candidates discuss the same topic.
                Therefore the action is MERGE.

                {"action":"MERGE","confidence":0.82,"reason":"same topic, high overlap"}
                """;
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        assertThat(extracted).startsWith("{").endsWith("}");
        assertThat(extracted).contains("MERGE");
    }

    @Test
    void unbalancedBracesReturnRemainderForCallerToFail() {
        // Don't silently succeed — let parseDecision throw with the raw text logged.
        String raw = "{\"action\":\"REVIEW\""; // truncated
        String extracted = AiRoutingEvaluatorService.extractJsonObject(raw);
        // The extractor returns the substring from `{` onwards so Jackson can throw
        // a meaningful parse error against an obviously truncated payload.
        assertThat(extracted).startsWith("{").contains("REVIEW");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(AiRoutingEvaluatorService.extractJsonObject(null)).isEmpty();
        assertThat(AiRoutingEvaluatorService.extractJsonObject("")).isEmpty();
        assertThat(AiRoutingEvaluatorService.extractJsonObject("   ")).isEmpty();
    }
}
