package com.rhizodelta.infrastructure.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 在 {@link ApplicationReadyEvent} 触发时打印一行结构化日志，
 * 列出 {@link FeatureFlagRegistry} 中所有 flag 的当前解析状态与来源。
 *
 * <p>输出格式示例：
 * <pre>{@code
 * Feature flags: observability=ENABLED(default), sweeper=DISABLED(default), proposal=DISABLED(default), ...
 * }</pre>
 *
 * <p><b>来源判定规则</b>：
 * <ul>
 *   <li>{@code env}：值由 {@code systemEnvironment} 属性源提供（即环境变量）</li>
 *   <li>{@code config}：值由非默认属性源提供（如 application.yml、命令行参数等）</li>
 *   <li>{@code default}：所有属性源都未提供该键，使用 registry 中的 default 值</li>
 * </ul>
 */
@Component
public class FeatureFlagLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagLogger.class);

    private final Environment environment;

    public FeatureFlagLogger(Environment environment) {
        this.environment = environment;
    }

    /**
     * 监听应用就绪事件，输出 feature flag 状态总览。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logFeatureFlags() {
        String summary = FeatureFlagRegistry.FLAGS.stream()
                .map(this::formatFlag)
                .collect(Collectors.joining(", "));
        LOGGER.info("Feature flags: {}", summary);
    }

    private String formatFlag(FeatureFlagRegistry.FeatureFlag flag) {
        String rawValue = environment.getProperty(flag.propertyKey());
        boolean resolved = rawValue == null
                ? flag.defaultValue()
                : Boolean.parseBoolean(rawValue);
        String state = resolved ? "ENABLED" : "DISABLED";
        String source = resolveSource(flag.propertyKey(), rawValue);
        return flag.module() + "=" + state + "(" + source + ")";
    }

    /**
     * 判定 flag 值的来源。
     *
     * <p>当属性源未提供该键时返回 {@code default}；
     * 当 {@code systemEnvironment} 属性源提供该键时返回 {@code env}；
     * 其余情况一律视为 {@code config}（覆盖 application.yml、命令行、自定义属性源等）。
     */
    private String resolveSource(String propertyKey, String rawValue) {
        if (rawValue == null) {
            return "default";
        }
        if (environment instanceof ConfigurableEnvironment configurable) {
            MutablePropertySources sources = configurable.getPropertySources();
            // Spring Boot 把环境变量映射到 propertyKey 时会做字母转换；
            // 这里依赖 Environment 自身的解析结果，仅判断是否来自 systemEnvironment 源。
            var envSource = sources.get("systemEnvironment");
            if (envSource != null && envSource.containsProperty(toEnvVarName(propertyKey))) {
                return "env";
            }
        }
        return "config";
    }

    /**
     * 把 {@code rhizodelta.feature.observability.enabled} 形式的属性键
     * 翻译为 {@code RHIZODELTA_FEATURE_OBSERVABILITY_ENABLED} 形式的环境变量名。
     *
     * <p>这是 Spring Boot 的 {@code SystemEnvironmentPropertySource} 接受的命名形式，
     * 用于反查该值是否真的来自环境变量。
     */
    private String toEnvVarName(String propertyKey) {
        return propertyKey.toUpperCase().replace('.', '_').replace('-', '_');
    }
}
