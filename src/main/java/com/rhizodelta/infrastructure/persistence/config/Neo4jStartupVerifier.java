package com.rhizodelta.infrastructure.persistence.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 在启动阶段验证 Neo4j 连通性。
 *
 * <p>该组件的目标是尽早暴露错误的数据库连接配置，
 * 而不是让系统在第一次真正访问数据库时才失败。
 */
@Component
public class Neo4jStartupVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(Neo4jStartupVerifier.class);

    private final Driver driver;
    private final Neo4jSettings neo4jSettings;

    public Neo4jStartupVerifier(Driver driver, Neo4jSettings neo4jSettings) {
        this.driver = driver;
        this.neo4jSettings = neo4jSettings;
    }

    /**
     * 在 Bean 初始化后验证数据库连接。
     *
     * <p>连接失败会直接中止应用启动。
     */
    @PostConstruct
    void verifyConnectivity() {
        try {
            driver.verifyConnectivity();
            LOGGER.info("Neo4j connectivity verified for {}", neo4jSettings.uri());
        } catch (Exception exception) {
            String message = String.format(
                    "Failed to connect to Neo4j at %s with user %s",
                    neo4jSettings.uri(),
                    neo4jSettings.authentication().username()
            );
            throw new IllegalStateException(message, exception);
        }
    }
}

/**
 * 表示 Neo4j 连接配置。
 */
@Validated
@ConfigurationProperties(prefix = "spring.neo4j")
record Neo4jSettings(
        @NotBlank String uri,
        @Valid Auth authentication
) {
    /**
     * 表示 Neo4j 基础认证配置。
     */
    record Auth(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }
}
