package com.rhizodelta.ai.shared.service;

import com.rhizodelta.ai.shared.domain.ModelProfile;
import com.rhizodelta.ai.shared.domain.ModelPurpose;
import com.rhizodelta.infrastructure.config.ModelRouterConfig;
import com.rhizodelta.infrastructure.observability.MeteredChatLanguageModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用途选择并缓存聊天模型实例。
 *
 * <p>该服务把"某个能力应该走哪个模型"的配置决策集中在一起，
 * 避免业务代码直接依赖模型名称、地址和鉴权信息。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>若某个用途没有专用模型，则回退到默认模型。</li>
 *   <li>专用模型会按用途缓存，避免重复创建底层客户端。</li>
 *   <li>非法用途配置会在构建配置映射时被显式忽略并记录日志。</li>
 *   <li>当 {@link MeterRegistry} Bean 存在时（即 {@code rhizodelta.feature.observability.enabled=true}），
 *       所有产出的 {@link ChatLanguageModel} 会被
 *       {@link MeteredChatLanguageModel} 透明包裹用于埋点；
 *       Bean 缺失时退化为返回原始模型，业务行为完全等同于 change 之前。</li>
 * </ul>
 */
@Service
public class ModelRouterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRouterService.class);

    private final ChatLanguageModel defaultModel;
    private final Map<ModelPurpose, ModelProfile> profiles;
    private final ConcurrentHashMap<ModelPurpose, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();
    private final Optional<MeterRegistry> meterRegistry;

    public ModelRouterService(
            ChatLanguageModel defaultModel,
            ModelRouterConfig config,
            Optional<MeterRegistry> meterRegistry
    ) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        this.profiles = buildProfiles(config);
        this.meterRegistry = meterRegistry;
        LOGGER.info("ModelRouterService initialized with {} purpose-specific profiles, "
                        + "purposes={}, observabilityEnabled={}",
                profiles.size(), profiles.keySet(), meterRegistry.isPresent());
    }

    /**
     * 返回指定用途对应的聊天模型实例。
     *
     * <p>若存在专用配置，则优先使用并缓存专用模型；否则回退到默认模型。
     * 当可观测性启用时，返回的模型已被 {@link MeteredChatLanguageModel} 装饰。
     */
    public ChatLanguageModel getModel(ModelPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        ModelProfile profile = profiles.get(purpose);
        if (profile == null) {
            LOGGER.debug("No dedicated model for purpose={}, using default", purpose);
            return decorate(defaultModel, resolveDefaultModelName(), purpose);
        }
        return modelCache.computeIfAbsent(purpose, p -> createModel(profile));
    }

    /**
     * 返回指定用途最终实际使用的模型名称。
     */
    public String resolveModelName(ModelPurpose purpose) {
        ModelProfile profile = profiles.get(purpose);
        if (profile != null) {
            return profile.name();
        }
        return resolveDefaultModelName();
    }

    /**
     * 根据模型配置构建新的聊天模型实例（必要时再用装饰器包一层）。
     */
    private ChatLanguageModel createModel(ModelProfile profile) {
        LOGGER.info("Creating ChatLanguageModel for purpose={} model={}", profile.purpose(), profile.name());
        ChatLanguageModel raw = OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.name())
                .maxTokens(profile.maxTokens())
                .build();
        return decorate(raw, profile.name(), profile.purpose());
    }

    /**
     * 在 MeterRegistry 存在时把模型包装为 {@link MeteredChatLanguageModel}。
     *
     * <p>缺失时直接返回原模型，确保 feature flag 关闭时业务行为完全无差异。
     */
    private ChatLanguageModel decorate(ChatLanguageModel raw, String modelName, ModelPurpose purpose) {
        return meterRegistry
                .<ChatLanguageModel>map(registry -> new MeteredChatLanguageModel(raw, registry, modelName, purpose))
                .orElse(raw);
    }

    private String resolveDefaultModelName() {
        if (defaultModel instanceof OpenAiChatModel openAiChatModel) {
            return openAiChatModel.modelName();
        }
        return defaultModel.getClass().getSimpleName();
    }

    /**
     * 从配置对象构建"用途 -> 模型配置"的映射。
     *
     * <p>无效用途、空模型名或空 API Key 会被主动忽略并记录日志。
     */
    private static Map<ModelPurpose, ModelProfile> buildProfiles(ModelRouterConfig config) {
        if (config == null || config.models() == null || config.models().isEmpty()) {
            return Map.of();
        }
        var builder = new java.util.HashMap<ModelPurpose, ModelProfile>();
        config.models().forEach((key, entry) -> {
            ModelPurpose purpose;
            try {
                purpose = ModelPurpose.valueOf(key.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring unknown model purpose key: {}", key);
                return;
            }
            if (entry.modelName() == null || entry.modelName().isBlank()) {
                LOGGER.warn("Skipping model purpose={} with blank model name", purpose);
                return;
            }
            if (entry.apiKey() == null || entry.apiKey().isBlank()) {
                LOGGER.warn("Skipping model purpose={} with blank API key", purpose);
                return;
            }
            builder.put(purpose, new ModelProfile(
                    entry.modelName(),
                    entry.baseUrl(),
                    entry.apiKey(),
                    purpose,
                    entry.maxTokens()
            ));
        });
        return Map.copyOf(builder);
    }
}
