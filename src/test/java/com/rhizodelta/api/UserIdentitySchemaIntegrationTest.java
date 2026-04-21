package com.rhizodelta.api;

import com.rhizodelta.RhizoDeltaApplication;
import com.rhizodelta.infrastructure.security.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.neo4j.driver.summary.Plan;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class UserIdentitySchemaIntegrationTest {
    private static final DockerImageName NEO4J_IMAGE = DockerImageName.parse("neo4j:5");
    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:3.13");
    private static final String NEO4J_PASSWORD = "testpassword";
    private static final String USER_ID_CONSTRAINT = "rhizodelta_user_account_user_id_unique";
    private static final String STATUS_INDEX = "rhizodelta_user_account_status_idx";
    private static final String UNIQUE_INDEX_SEEK = "NodeUniqueIndexSeek";
    private static final String SHOW_CONSTRAINTS_QUERY = """
            SHOW CONSTRAINTS
            YIELD name
            WHERE name STARTS WITH 'rhizodelta_'
            RETURN name
            ORDER BY name
            """;
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
    void cleanBootShouldExposeUserIdConstraint() throws Exception {
        withStartedApplication(driver -> {
        }, app -> assertThat(queryNames(app.driver(), SHOW_CONSTRAINTS_QUERY)).contains(USER_ID_CONSTRAINT));
    }
    @Test
    void cleanBootShouldExposeUserAccountIndexes() throws Exception {
        withStartedApplication(driver -> {
        }, app -> assertThat(queryNames(app.driver(), SHOW_INDEXES_QUERY)).contains(STATUS_INDEX));
    }
    @Test
    void explainUserIdLookupShouldUseUniqueIndexSeek() throws Exception {
        withStartedApplication(driver -> {
        }, app -> assertThat(explainPlanContainsOperator(app.driver(), "test-user-id", UNIQUE_INDEX_SEEK)).isTrue());
    }
    @Test
    void registerShouldPersistActiveStatus() throws Exception {
        withStartedApplication(driver -> {
        }, app -> {
            TestRestTemplate restTemplate = new TestRestTemplate();
            var response = restTemplate.postForEntity(
                    app.baseUrl() + "/api/auth/register",
                    Map.of("username", "alice", "password", "password123", "display_name", "Alice"),
                    Map.class
            );
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(fetchUserStatus(app.driver(), "alice")).isEqualTo(UserStatus.ACTIVE.name());
        });
    }

    @Test
    void startupShouldFailWhenSeededUserHasNullUserId() {
        Throwable failure = startApplicationFailure(driver -> createUserWithoutUserId(driver, "null-user"));

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure).getMessage()).contains("null-user");
    }

    @Test
    void startupShouldFailWhenSeededUserHasWhitespaceUserId() {
        Throwable failure = startApplicationFailure(
                driver -> createUserWithWhitespaceUserId(driver, "whitespace-user"));

        assertThat(failure).isNotNull();
        String message = rootCause(failure).getMessage();
        assertThat(message).contains("whitespace-user");
        assertThat(message).contains("blank user_id");
    }

    @Test
    void startupShouldFailWhenSeededUsersShareDuplicateUserId() {
        Throwable failure = startApplicationFailure(driver -> {
            createUser(driver, "dup-a", "dup-1");
            createUser(driver, "dup-b", "dup-1");
        });

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure).getMessage()).contains("dup-1");
    }

    @Test
    void directNeo4jClientCreateShouldRejectDuplicateUserId() throws Exception {
        withStartedApplication(driver -> {
        }, app -> {
            Neo4jClient neo4jClient = app.context().getBean(Neo4jClient.class);
            createUserWithNeo4jClient(neo4jClient, "first-user", "dup-2");

            assertThatThrownBy(() -> createUserWithNeo4jClient(neo4jClient, "second-user", "dup-2"))
                    .hasStackTraceContaining("ConstraintValidationFailed");
        });
    }

    @Test
    void cleanPreCheckShouldLeaveUserPropertiesUnchanged() throws Exception {
        withStartedApplication(driver -> createUser(driver, "snapshot-user", "snapshot-1"), app -> {
            List<Map<String, Object>> snapshotAfterBoot = snapshotUserAccounts(app.driver());
            assertThat(snapshotAfterBoot).containsExactlyElementsOf(app.seedSnapshot());
        });
    }

    private void withStartedApplication(DriverConsumer seeder, StartedApplicationConsumer assertions) throws Exception {
        try (Neo4jContainer<?> neo4j = new Neo4jContainer<>(NEO4J_IMAGE).withAdminPassword(NEO4J_PASSWORD)) {
            neo4j.start();
            try (Driver driver = newDriver(neo4j)) {
                seeder.accept(driver);
                List<Map<String, Object>> seedSnapshot = snapshotUserAccounts(driver);
                try (ConfigurableApplicationContext context = startApplication(neo4j)) {
                    assertions.accept(new StartedApplication(context, driver, seedSnapshot, baseUrl(context)));
                }
            }
        }
    }

    private Throwable startApplicationFailure(DriverConsumer seeder) {
        try (Neo4jContainer<?> neo4j = new Neo4jContainer<>(NEO4J_IMAGE).withAdminPassword(NEO4J_PASSWORD)) {
            neo4j.start();
            try (Driver driver = newDriver(neo4j)) {
                seeder.accept(driver);
                return catchThrowable(() -> {
                    try (ConfigurableApplicationContext ignored = startApplication(neo4j)) {
                    }
                });
            }
        } catch (Exception exception) {
            return exception;
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

    private static Driver newDriver(Neo4jContainer<?> neo4j) {
        return GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J_PASSWORD));
    }

    private static void createUser(Driver driver, String username, String userId) {
        runWrite(driver, """
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  display_name: $username,
                  password_hash: 'hash',
                  roles: ['USER'],
                  created_at: datetime()
                })
                """, Map.of("username", username, "userId", userId));
    }

    private static void createUserWithoutUserId(Driver driver, String username) {
        runWrite(driver, """
                CREATE (:UserAccount {
                  username: $username,
                  display_name: $username,
                  password_hash: 'hash',
                  roles: ['USER'],
                  created_at: datetime()
                })
                """, Map.of("username", username));
    }

    private static void createUserWithWhitespaceUserId(Driver driver, String username) {
        runWrite(driver, """
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  display_name: $username,
                  password_hash: 'hash',
                  roles: ['USER'],
                  created_at: datetime()
                })
                """, Map.of("username", username, "userId", "   "));
    }

    private static void createUserWithNeo4jClient(Neo4jClient neo4jClient, String username, String userId) {
        neo4jClient.query("""
                CREATE (:UserAccount {
                  username: $username,
                  user_id: $userId,
                  display_name: $username,
                  password_hash: 'hash',
                  roles: ['USER'],
                  status: $statusValue,
                  created_at: datetime()
                })
                """)
                .bindAll(Map.of(
                        "username", username,
                        "userId", userId,
                        "statusValue", UserStatus.ACTIVE.name()
                ))
                .run();
    }

    private static void runWrite(Driver driver, String query, Map<String, Object> params) {
        driver.executableQuery(query).withParameters(params).execute();
    }

    private static List<String> queryNames(Driver driver, String query) {
        return driver.executableQuery(query)
                .execute()
                .records()
                .stream()
                .map(record -> record.get("name").asString())
                .toList();
    }

    private static boolean explainPlanContainsOperator(Driver driver, String userId, String operator) {
        Plan plan = driver.executableQuery("EXPLAIN MATCH (u:UserAccount {user_id: $userId}) RETURN u")
                .withParameters(Map.of("userId", userId))
                .execute()
                .summary()
                .plan();
        return planContainsOperator(plan, operator);
    }

    private static boolean planContainsOperator(Plan plan, String operator) {
        if (plan.operatorType().contains(operator)) {
            return true;
        }
        return plan.children().stream().anyMatch(child -> planContainsOperator(child, operator));
    }

    private static String fetchUserStatus(Driver driver, String username) {
        return driver.executableQuery("""
                MATCH (user:UserAccount {username: $username})
                RETURN user.status AS status
                """)
                .withParameters(Map.of("username", username))
                .execute()
                .records()
                .get(0)
                .get("status")
                .asString();
    }

    private static List<Map<String, Object>> snapshotUserAccounts(Driver driver) {
        return driver.executableQuery("""
                MATCH (user:UserAccount)
                RETURN user.username AS username, properties(user) AS props
                ORDER BY user.username
                """)
                .execute()
                .records()
                .stream()
                .map(record -> Map.<String, Object>of(
                        "username", record.get("username").asString(),
                        "props", record.get("props").asMap()
                ))
                .toList();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "http://localhost:" + port;
    }

    private record StartedApplication(
            ConfigurableApplicationContext context,
            Driver driver,
            List<Map<String, Object>> seedSnapshot,
            String baseUrl
    ) {
    }

    @FunctionalInterface
    private interface DriverConsumer {
        void accept(Driver driver) throws Exception;
    }

    @FunctionalInterface
    private interface StartedApplicationConsumer {
        void accept(StartedApplication application) throws Exception;
    }
}
