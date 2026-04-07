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

@Component
public class Neo4jStartupVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(Neo4jStartupVerifier.class);

    private final Driver driver;
    private final Neo4jSettings neo4jSettings;

    public Neo4jStartupVerifier(Driver driver, Neo4jSettings neo4jSettings) {
        this.driver = driver;
        this.neo4jSettings = neo4jSettings;
    }

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

@Validated
@ConfigurationProperties(prefix = "spring.neo4j")
record Neo4jSettings(
        @NotBlank String uri,
        @Valid Auth authentication
) {
    record Auth(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }
}
