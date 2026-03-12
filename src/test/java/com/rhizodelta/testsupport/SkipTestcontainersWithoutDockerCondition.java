package com.rhizodelta.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

/**
 * 在无 Docker daemon 的环境中，统一跳过所有 @Testcontainers 集成测试。
 */
public final class SkipTestcontainersWithoutDockerCondition implements ExecutionCondition {
    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker daemon is available or class is not @Testcontainers.");
    private static final String DISABLED_REASON =
            "Docker daemon is unavailable, skipping @Testcontainers integration tests.";

    private static volatile Boolean dockerAvailable;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        if (testClass.isEmpty() || !testClass.get().isAnnotationPresent(Testcontainers.class)) {
            return ENABLED;
        }

        if (isDockerAvailable()) {
            return ENABLED;
        }
        return ConditionEvaluationResult.disabled(DISABLED_REASON);
    }

    private boolean isDockerAvailable() {
        if (dockerAvailable != null) {
            return dockerAvailable;
        }
        synchronized (SkipTestcontainersWithoutDockerCondition.class) {
            if (dockerAvailable == null) {
                dockerAvailable = detectDockerAvailability();
            }
            return dockerAvailable;
        }
    }

    private static boolean detectDockerAvailability() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
