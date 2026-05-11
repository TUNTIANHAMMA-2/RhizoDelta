package com.rhizodelta.infrastructure.observability;

import com.rhizodelta.ai.shared.domain.ModelPurpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeteredChatLanguageModelTest {

    private static final String MODEL = "deepseek-v4-flash";
    private static final ModelPurpose PURPOSE = ModelPurpose.SUMMARY;
    private static final List<ChatMessage> MESSAGES = List.of(UserMessage.from("hello"));

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void successfulCallIncrementsTokenAndLatencyCounters() {
        AiMessage aiMessage = AiMessage.from("response");
        Response<AiMessage> stub = new Response<>(aiMessage, new TokenUsage(120, 80, 200), null);
        ChatLanguageModel delegate = messages -> stub;

        MeteredChatLanguageModel decorated = new MeteredChatLanguageModel(delegate, registry, MODEL, PURPOSE);

        Response<AiMessage> result = decorated.generate(MESSAGES);

        assertThat(result).isSameAs(stub);
        Counter inputCounter = registry.find("ai.llm.tokens").tag("kind", "input").counter();
        Counter outputCounter = registry.find("ai.llm.tokens").tag("kind", "output").counter();
        assertThat(inputCounter).isNotNull();
        assertThat(outputCounter).isNotNull();
        assertThat(inputCounter.count()).isEqualTo(120.0);
        assertThat(outputCounter.count()).isEqualTo(80.0);

        Timer timer = registry.find("ai.llm.latency").tag("model", MODEL).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);

        assertThat(registry.find("ai.llm.errors").counter()).isNull();
    }

    @Test
    void nullTokenUsageDoesNotIncrementCounter() {
        AiMessage aiMessage = AiMessage.from("response");
        Response<AiMessage> stub = new Response<>(aiMessage, null, null);
        ChatLanguageModel delegate = messages -> stub;

        MeteredChatLanguageModel decorated = new MeteredChatLanguageModel(delegate, registry, MODEL, PURPOSE);

        Response<AiMessage> result = decorated.generate(MESSAGES);

        assertThat(result).isSameAs(stub);
        assertThat(registry.find("ai.llm.tokens").counter()).isNull();

        Timer timer = registry.find("ai.llm.latency").tag("model", MODEL).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void zeroTotalTokenCountDoesNotIncrementCounter() {
        AiMessage aiMessage = AiMessage.from("response");
        Response<AiMessage> stub = new Response<>(aiMessage, new TokenUsage(0, 0, 0), null);
        ChatLanguageModel delegate = messages -> stub;

        MeteredChatLanguageModel decorated = new MeteredChatLanguageModel(delegate, registry, MODEL, PURPOSE);

        decorated.generate(MESSAGES);

        assertThat(registry.find("ai.llm.tokens").counter()).isNull();
        assertThat(registry.find("ai.llm.latency").timer()).isNotNull();
    }

    @Test
    void exceptionIsRethrownAndErrorCounterIncremented() {
        IllegalStateException original = new IllegalStateException("boom");
        ChatLanguageModel delegate = messages -> {
            throw original;
        };

        MeteredChatLanguageModel decorated = new MeteredChatLanguageModel(delegate, registry, MODEL, PURPOSE);

        assertThatThrownBy(() -> decorated.generate(MESSAGES))
                .isSameAs(original);

        Counter errorCounter = registry.find("ai.llm.errors")
                .tag("model", MODEL)
                .tag("purpose", PURPOSE.name())
                .tag("exception", "IllegalStateException")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);

        Timer latencyTimer = registry.find("ai.llm.latency").timer();
        assertThat(latencyTimer).isNotNull();
        assertThat(latencyTimer.count()).isEqualTo(1L);
    }

    @Test
    void inputOnlyTokenIsRecordedWhenOutputIsZero() {
        Response<AiMessage> stub = new Response<>(
                AiMessage.from("response"),
                new TokenUsage(50, 0, 50),
                null);
        ChatLanguageModel delegate = messages -> stub;

        MeteredChatLanguageModel decorated = new MeteredChatLanguageModel(delegate, registry, MODEL, PURPOSE);

        decorated.generate(MESSAGES);

        Counter input = registry.find("ai.llm.tokens").tag("kind", "input").counter();
        Counter output = registry.find("ai.llm.tokens").tag("kind", "output").counter();
        assertThat(input).isNotNull();
        assertThat(input.count()).isEqualTo(50.0);
        assertThat(output).isNull();
    }
}
