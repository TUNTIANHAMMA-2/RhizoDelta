package com.rhizodelta.infrastructure.observability;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagLoggerTest {

    @Test
    void formatsAllRegisteredFlagsWithDefaultSourceWhenUnset() {
        MockEnvironment env = new MockEnvironment();
        FeatureFlagLogger logger = new FeatureFlagLogger(env);

        String summary = capture(logger);

        assertThat(summary).contains("observability=ENABLED(default)");
        assertThat(summary).contains("sweeper=DISABLED(default)");
        assertThat(summary).contains("proposal=DISABLED(default)");
        assertThat(summary).contains("prefers-aggregation=DISABLED(default)");
        assertThat(summary).contains("prefers-feed-ranking=DISABLED(default)");
    }

    @Test
    void detectsConfigSourceWhenPropertySetViaApplicationYml() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("rhizodelta.feature.observability.enabled", "false");

        FeatureFlagLogger logger = new FeatureFlagLogger(env);
        String summary = capture(logger);

        assertThat(summary).contains("observability=DISABLED(config)");
    }

    @Test
    void detectsEnvSourceWhenPropertyComesFromSystemEnvironment() {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.replace("systemEnvironment", new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("RHIZODELTA_FEATURE_OBSERVABILITY_ENABLED", "false")
        ));

        FeatureFlagLogger logger = new FeatureFlagLogger(env);
        String summary = capture(logger);

        assertThat(summary).contains("observability=DISABLED(env)");
    }

    /**
     * 把 logger 输出的 "observability=...(...)" 格式重建一份用于断言。
     *
     * <p>{@link FeatureFlagLogger#logFeatureFlags()} 直接走 SLF4J 没有返回值，
     * 这里复用其内部格式化逻辑（package-private 访问）以避免引入额外的 log capture 依赖。
     */
    private static String capture(FeatureFlagLogger logger) {
        StringBuilder sb = new StringBuilder();
        for (FeatureFlagRegistry.FeatureFlag flag : FeatureFlagRegistry.FLAGS) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(invokeFormat(logger, flag));
        }
        return sb.toString();
    }

    private static String invokeFormat(FeatureFlagLogger logger, FeatureFlagRegistry.FeatureFlag flag) {
        try {
            var method = FeatureFlagLogger.class.getDeclaredMethod("formatFlag", FeatureFlagRegistry.FeatureFlag.class);
            method.setAccessible(true);
            return (String) method.invoke(logger, flag);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke formatFlag via reflection", e);
        }
    }
}
