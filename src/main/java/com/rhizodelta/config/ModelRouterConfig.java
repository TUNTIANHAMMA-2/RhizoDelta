package com.rhizodelta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "rhizodelta.ai")
public record ModelRouterConfig(
        Map<String, ModelEntry> models,
        RulesConfig rules
) {
    public ModelRouterConfig {
        if (models == null) {
            models = Map.of();
        }
        if (rules == null) {
            rules = new RulesConfig(0.9, 0.3);
        }
    }

    public record ModelEntry(
            String baseUrl,
            String modelName,
            String apiKey,
            int maxTokens
    ) {
        public ModelEntry {
            if (maxTokens <= 0) {
                maxTokens = 4096;
            }
        }
    }

    public record RulesConfig(
            double autoMergeThreshold,
            double autoBranchThreshold
    ) {
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
