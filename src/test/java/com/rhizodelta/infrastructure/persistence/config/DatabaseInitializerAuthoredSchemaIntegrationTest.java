package com.rhizodelta.infrastructure.persistence.config;

import com.rhizodelta.RhizoDeltaApplication;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DatabaseInitializerAuthoredSchemaIntegrationTest {
    private static final DockerImageName NEO4J_IMAGE = DockerImageName.parse("neo4j:5");
    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:3.13");
    private static final String NEO4J_PASSWORD = "testpassword";
    private static final String AUTHORED_CREATED_AT_INDEX = "rhizodelta_authored_created_at_idx";
    private static final String SHOW_INDEXES_QUERY = """
            SHOW INDEXES
            YIELD name
            WHERE name STARTS WITH 'rhizodelta_'
            RETURN name
            ORDER BY name
            """;

    @Container
    static final RabbitMQContainer rabbitMq = new RabbitMQContainer(RABBIT_IMAGE);

    @Test
    void cleanBootShouldExposeAuthoredCreatedAtIndex() throws Exception {
        try (Neo4jContainer<?> neo4j = new Neo4jContainer<>(NEO4J_IMAGE).withAdminPassword(NEO4J_PASSWORD)) {
            neo4j.start();
            try (Driver driver = GraphDatabase.driver(
                    neo4j.getBoltUrl(),
                    AuthTokens.basic("neo4j", NEO4J_PASSWORD)
            )) {
                try (ConfigurableApplicationContext context = startApplication(neo4j)) {
                    assertThat(queryNames(driver)).contains(AUTHORED_CREATED_AT_INDEX);
                }
            }
        }
    }

    private static ConfigurableApplicationContext startApplication(Neo4jContainer<?> neo4j) {
        SpringApplication application = new SpringApplication(RhizoDeltaApplication.class);
        return application.run(
                "--spring.profiles.active=test",
                "--server.port=0",
                "--spring.neo4j.uri=" + neo4j.getBoltUrl(),
                "--spring.neo4j.authentication.username=neo4j",
                "--spring.neo4j.authentication.password=" + NEO4J_PASSWORD,
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=guest",
                "--spring.rabbitmq.password=guest",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.rabbitmq.listener.direct.auto-startup=false",
                "--langchain4j.open-ai.chat-model.api-key=test-api-key",
                "--langchain4j.open-ai.embedding-model.api-key=test-api-key"
        );
    }

    private static List<String> queryNames(Driver driver) {
        return driver.executableQuery(SHOW_INDEXES_QUERY)
                .execute()
                .records()
                .stream()
                .map(record -> record.get("name").asString())
                .toList();
    }
}
