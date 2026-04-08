package com.rhizodelta.ai.shared.domain;

/**
 * 描述一个按用途绑定的模型配置。
 *
 * <p>该对象承载模型名称、服务地址、凭据、用途和 token 上限，
 * 供模型路由服务在运行期构建实际模型实例。
 */
public record ModelProfile(
        String name,
        String baseUrl,
        String apiKey,
        ModelPurpose purpose,
        int maxTokens
) {
    /**
     * 创建模型配置并校验关键字段。
     *
     * <p>当 {@code maxTokens} 非法时，会回退到系统默认值，避免空配置导致运行期构建失败。
     */
    public ModelProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model name must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("base URL must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("purpose must not be null");
        }
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
    }
}
