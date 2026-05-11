package com.rhizodelta.infrastructure.observability;

import com.rhizodelta.ai.shared.domain.ModelPurpose;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 透明装饰 {@link ChatLanguageModel}，对每次 LLM 调用统一埋点。
 *
 * <p>该装饰器仅观察、不变更业务行为：返回的 {@link Response} 引用与 delegate 完全一致；
 * 抛出的异常与 delegate 一致；token usage 数据从 {@link Response#tokenUsage()} 读取，
 * 与业务调用现场无关。
 *
 * <p><b>埋点契约</b>（详见 specs/observability/spec.md）：
 * <ul>
 *   <li>{@code ai.llm.tokens{model, purpose, kind=input|output}} —— Counter，按调用累计</li>
 *   <li>{@code ai.llm.latency{model, purpose}} —— Timer，覆盖成功与失败两条路径</li>
 *   <li>{@code ai.llm.errors{model, purpose, exception}} —— Counter，异常类 SimpleName 截断 64 字符</li>
 * </ul>
 *
 * <p><b>tokenUsage 降级</b>：当返回的 token usage 为 {@code null} 或
 * {@code totalTokenCount <= 0} 时，写一条 WARN 日志（含 model + purpose），
 * <b>不递增 Counter</b>，避免污染调用次数统计。
 *
 * <p>本类只覆盖 abstract {@code generate(List&lt;ChatMessage&gt;)}：接口的 default 方法
 * （{@code generate(String)}、{@code generate(ChatMessage...)} 等）内部通过 {@code this.generate(...)}
 * 分发，会被本装饰器拦截，因此一处覆盖即覆盖全部入口。
 */
public class MeteredChatLanguageModel implements ChatLanguageModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeteredChatLanguageModel.class);

    private static final int EXCEPTION_TAG_MAX_LENGTH = 64;
    private static final String TOKENS_METRIC = "ai.llm.tokens";
    private static final String LATENCY_METRIC = "ai.llm.latency";
    private static final String ERRORS_METRIC = "ai.llm.errors";

    private final ChatLanguageModel delegate;
    private final MeterRegistry registry;
    private final String modelName;
    private final ModelPurpose purpose;
    // TODO V2: 当 DashScope 返回的 tokenUsage 命中率持续偏低时，
    // 在此处接入本地 tokenizer 估算（如 jtokkit），用 input/output 文本估算 token 数。
    // 当前按 D3 决策保持 WARN 日志 + 不递增 Counter 的降级策略。

    public MeteredChatLanguageModel(ChatLanguageModel delegate,
                                    MeterRegistry registry,
                                    String modelName,
                                    ModelPurpose purpose) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return record(() -> delegate.generate(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                        List<ToolSpecification> toolSpecifications) {
        return record(() -> delegate.generate(messages, toolSpecifications));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                        ToolSpecification toolSpecification) {
        return record(() -> delegate.generate(messages, toolSpecification));
    }

    /**
     * 统一埋点骨架：测量 latency、记录 token、捕获异常、原样重抛。
     */
    private Response<AiMessage> record(Supplier<Response<AiMessage>> call) {
        long startNanos = System.nanoTime();
        try {
            Response<AiMessage> response = call.get();
            long elapsedNanos = System.nanoTime() - startNanos;
            recordLatency(elapsedNanos);
            recordTokens(response);
            return response;
        } catch (RuntimeException ex) {
            long elapsedNanos = System.nanoTime() - startNanos;
            recordLatency(elapsedNanos);
            recordError(ex);
            throw ex;
        }
    }

    private void recordLatency(long elapsedNanos) {
        Timer.builder(LATENCY_METRIC)
                .tag("model", modelName)
                .tag("purpose", purpose.name())
                .description("LLM call wall-clock latency, including failures")
                // 开启 histogram bucket 输出，让 Prometheus 端能用 histogram_quantile() 算 P50/P95/P99。
                // 默认关闭是为了节省存储（每个 Timer 会展开为 ~14 个 _bucket 时序）；
                // 这里我们对 LLM 延迟分布很关心，且时序数受 model + purpose 双标签约束（≤20 个 Timer），
                // 总 bucket 时序约 280 个，仍远低于 D6 设定的 1024 上限。
                .publishPercentileHistogram(true)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private void recordTokens(Response<AiMessage> response) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage == null
                || usage.totalTokenCount() == null
                || usage.totalTokenCount() <= 0) {
            LOGGER.warn(
                    "LLM call returned no usable tokenUsage; model={}, purpose={}. "
                            + "Counter ai.llm.tokens not incremented to preserve metric semantics.",
                    modelName, purpose);
            return;
        }
        Integer input = usage.inputTokenCount();
        Integer output = usage.outputTokenCount();
        if (input != null && input > 0) {
            tokenCounter("input").increment(input);
        }
        if (output != null && output > 0) {
            tokenCounter("output").increment(output);
        }
    }

    private Counter tokenCounter(String kind) {
        return Counter.builder(TOKENS_METRIC)
                .tag("model", modelName)
                .tag("purpose", purpose.name())
                .tag("kind", kind)
                .description("LLM token consumption, split by input/output kind")
                .register(registry);
    }

    private void recordError(RuntimeException ex) {
        String exceptionTag = truncate(ex.getClass().getSimpleName());
        Counter.builder(ERRORS_METRIC)
                .tag("model", modelName)
                .tag("purpose", purpose.name())
                .tag("exception", exceptionTag)
                .description("LLM call exceptions tagged by exception simple name")
                .register(registry)
                .increment();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown";
        }
        return value.length() <= EXCEPTION_TAG_MAX_LENGTH
                ? value
                : value.substring(0, EXCEPTION_TAG_MAX_LENGTH);
    }
}
