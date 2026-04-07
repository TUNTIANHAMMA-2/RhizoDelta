package com.rhizodelta.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class EmbeddingTaskExecutorConfig {
    private static final String CORE_POOL_SIZE_PROPERTY = "rhizodelta.embedding.executor.corePoolSize";
    private static final String MAX_POOL_SIZE_PROPERTY = "rhizodelta.embedding.executor.maxPoolSize";
    private static final String QUEUE_CAPACITY_PROPERTY = "rhizodelta.embedding.executor.queueCapacity";
    private static final int DEFAULT_CORE_POOL_SIZE = 4;
    private static final int DEFAULT_MAX_POOL_SIZE = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final String THREAD_NAME_PREFIX = "embedding-";

    private static final String ROUTING_CORE_POOL_SIZE_PROPERTY = "rhizodelta.routing.executor.corePoolSize";
    private static final String ROUTING_MAX_POOL_SIZE_PROPERTY = "rhizodelta.routing.executor.maxPoolSize";
    private static final String ROUTING_QUEUE_CAPACITY_PROPERTY = "rhizodelta.routing.executor.queueCapacity";
    private static final int ROUTING_DEFAULT_CORE_POOL_SIZE = 2;
    private static final int ROUTING_DEFAULT_MAX_POOL_SIZE = 4;
    private static final int ROUTING_DEFAULT_QUEUE_CAPACITY = 50;
    private static final String ROUTING_THREAD_NAME_PREFIX = "routing-";

    @Bean(name = "embeddingTaskExecutor")
    public Executor embeddingTaskExecutor(Environment environment) {
        int corePoolSize = resolvePositiveInt(environment, CORE_POOL_SIZE_PROPERTY, DEFAULT_CORE_POOL_SIZE);
        int maxPoolSize = resolvePositiveInt(environment, MAX_POOL_SIZE_PROPERTY, DEFAULT_MAX_POOL_SIZE);
        int queueCapacity = resolvePositiveInt(environment, QUEUE_CAPACITY_PROPERTY, DEFAULT_QUEUE_CAPACITY);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    @Bean(name = "routingTaskExecutor")
    public Executor routingTaskExecutor(Environment environment) {
        int corePoolSize = resolvePositiveInt(environment, ROUTING_CORE_POOL_SIZE_PROPERTY, ROUTING_DEFAULT_CORE_POOL_SIZE);
        int maxPoolSize = resolvePositiveInt(environment, ROUTING_MAX_POOL_SIZE_PROPERTY, ROUTING_DEFAULT_MAX_POOL_SIZE);
        int queueCapacity = resolvePositiveInt(environment, ROUTING_QUEUE_CAPACITY_PROPERTY, ROUTING_DEFAULT_QUEUE_CAPACITY);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(ROUTING_THREAD_NAME_PREFIX);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    private static int resolvePositiveInt(Environment environment, String key, int defaultValue) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + value, exception);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive, got: " + parsed);
        }
        return parsed;
    }
}
