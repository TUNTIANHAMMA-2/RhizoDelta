package com.rhizodelta.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 在启动阶段校验 OpenAI 相关 API Key 配置。
 *
 * <p>该组件的作用不是运行期兜底，而是在应用启动时尽早暴露空密钥或占位密钥配置错误，
 * 避免系统在第一次真实调用模型时才延迟失败。
 */
@Component
public class OpenAiApiKeyStartupValidator {
    static final String PLACEHOLDER_API_KEY = "your-placeholder-key";

    private final String chatModelApiKey;
    private final String embeddingModelApiKey;

    public OpenAiApiKeyStartupValidator(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String chatModelApiKey,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String embeddingModelApiKey
    ) {
        this.chatModelApiKey = chatModelApiKey;
        this.embeddingModelApiKey = embeddingModelApiKey;
    }

    /**
     * 在 Bean 初始化后立即校验关键密钥。
     *
     * <p>一旦校验失败，应用启动会直接中止。
     */
    @PostConstruct
    void validate() {
        validateKey(chatModelApiKey, "langchain4j.open-ai.chat-model.api-key");
        validateKey(embeddingModelApiKey, "langchain4j.open-ai.embedding-model.api-key");
    }

    /**
     * 校验单个 API Key 配置项。
     */
    private void validateKey(String apiKey, String propertyName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        if (PLACEHOLDER_API_KEY.equals(apiKey)) {
            throw new IllegalStateException(propertyName + " must not use the placeholder API key");
        }
    }
}
