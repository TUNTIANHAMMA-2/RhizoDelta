package com.rhizodelta.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiApiKeyStartupValidatorTest {

    @Test
    void validateShouldRejectPlaceholderApiKey() {
        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(
                OpenAiApiKeyStartupValidator.PLACEHOLDER_API_KEY,
                "real-embedding-key"
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("langchain4j.open-ai.chat-model.api-key must not use the placeholder API key");
    }

    @Test
    void validateShouldRejectBlankApiKey() {
        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(
                "real-chat-key",
                " "
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("langchain4j.open-ai.embedding-model.api-key must not be blank");
    }

    @Test
    void validateShouldAllowConfiguredApiKeys() {
        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(
                "real-chat-key",
                "real-embedding-key"
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}
