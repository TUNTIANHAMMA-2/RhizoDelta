package com.rhizodelta.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 声明 AI 模型路由相关配置。
 *
 * <p>该配置对象承载按用途划分的模型清单，以及规则预过滤阶段使用的阈值配置。
 */
@ConfigurationProperties(prefix = "rhizodelta.ai")
public record ModelRouterConfig(
        Map<String, ModelEntry> models,
        RulesConfig rules
) {
    /**
     * 创建模型路由配置并补齐默认值。
     */
    public ModelRouterConfig {
        if (models == null) {
            models = Map.of();
        }
        if (rules == null) {
            rules = new RulesConfig(0.9, 0.3);
        }
    }

    /**
     * 表示单个模型配置项。
     */
    public record ModelEntry(
            String baseUrl,
            String modelName,
            String apiKey,
            int maxTokens
    ) {
        /**
         * 创建模型配置项并补齐默认 token 上限。
         */
        public ModelEntry {
            if (maxTokens <= 0) {
                maxTokens = 4096;
            }
        }
    }

    /**
     * 表示规则预过滤阈值配置。
     */
    public record RulesConfig(
            double autoMergeThreshold,
            double autoBranchThreshold
    ) {
        /**
         * 创建规则配置并补齐默认阈值。
         */
        public RulesConfig {
            if (autoMergeThreshold <= 0) {
                autoMergeThreshold = 0.9;
            }
            if (autoBranchThreshold < 0) {
                autoBranchThreshold = 0.3;
            }
        }
    }
}
