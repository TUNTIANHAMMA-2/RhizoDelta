package com.rhizodelta.service;

import com.rhizodelta.config.ModelRouterConfig;
import com.rhizodelta.domain.ai.ModelProfile;
import com.rhizodelta.domain.ai.ModelPurpose;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRouterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRouterService.class);

    private final ChatLanguageModel defaultModel;
    private final Map<ModelPurpose, ModelProfile> profiles;
    private final ConcurrentHashMap<ModelPurpose, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();

    public ModelRouterService(
            ChatLanguageModel defaultModel,
            ModelRouterConfig config
    ) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        this.profiles = buildProfiles(config);
        LOGGER.info("ModelRouterService initialized with {} purpose-specific profiles, purposes={}",
                profiles.size(), profiles.keySet());
    }

    public ChatLanguageModel getModel(ModelPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        ModelProfile profile = profiles.get(purpose);
        if (profile == null) {
            LOGGER.debug("No dedicated model for purpose={}, using default", purpose);
            return defaultModel;
        }
        return modelCache.computeIfAbsent(purpose, p -> createModel(profile));
    }

    public String resolveModelName(ModelPurpose purpose) {
        ModelProfile profile = profiles.get(purpose);
        if (profile != null) {
            return profile.name();
        }
        if (defaultModel instanceof OpenAiChatModel openAiChatModel) {
            return openAiChatModel.modelName();
        }
        return defaultModel.getClass().getSimpleName();
    }

    private ChatLanguageModel createModel(ModelProfile profile) {
        LOGGER.info("Creating ChatLanguageModel for purpose={} model={}", profile.purpose(), profile.name());
        return OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.name())
                .maxTokens(profile.maxTokens())
                .build();
    }

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
