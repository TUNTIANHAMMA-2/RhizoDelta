package com.rhizodelta.infrastructure.persistence.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jDeploymentConfigTest {
    private static final Path APPLICATION_YAML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path APPLICATION_LOCAL_YAML_PATH = Path.of("src/main/resources/application-local.yml");
    private static final Path COMPOSE_YAML_PATH = Path.of("docker-compose.yml");
    // Matches both ${VAR} and ${VAR:default}
    private static final Pattern APP_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::([^}]+))?}");

    @Test
    void neo4jUriShouldReferenceEnvVar() throws Exception {
        Placeholder uri = extractAppPlaceholder("spring.neo4j.uri");
        assertThat(uri.key()).isEqualTo("NEO4J_URI");
    }

    @Test
    void neo4jAuthShouldReferenceEnvVars() throws Exception {
        Placeholder username = extractAppPlaceholder("spring.neo4j.authentication.username");
        Placeholder password = extractAppPlaceholder("spring.neo4j.authentication.password");
        assertThat(username.key()).isEqualTo("NEO4J_USERNAME");
        assertThat(password.key()).isEqualTo("NEO4J_PASSWORD");
    }

    @Test
    void composeNeo4jShouldDeclareAuthEnvVar() throws Exception {
        Map<String, Object> root = loadYaml(COMPOSE_YAML_PATH);
        Object rawValue = findByPath(root, "services.neo4j.environment.NEO4J_AUTH");
        assertThat(rawValue).isNotNull();
    }

    @Test
    void localProfileInfrastructureSettingsShouldReferenceEnvVars() throws Exception {
        Placeholder neo4jUri = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.neo4j.uri");
        Placeholder neo4jUsername = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.neo4j.authentication.username");
        Placeholder neo4jPassword = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.neo4j.authentication.password");
        Placeholder rabbitHost = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.rabbitmq.host");
        Placeholder rabbitPort = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.rabbitmq.port");
        Placeholder rabbitUsername = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.rabbitmq.username");
        Placeholder rabbitPassword = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.rabbitmq.password");
        Placeholder redisHost = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.data.redis.host");
        Placeholder redisPort = extractPlaceholder(APPLICATION_LOCAL_YAML_PATH, "spring.data.redis.port");

        assertThat(neo4jUri.key()).isEqualTo("NEO4J_URI");
        assertThat(neo4jUsername.key()).isEqualTo("NEO4J_USERNAME");
        assertThat(neo4jPassword.key()).isEqualTo("NEO4J_PASSWORD");
        assertThat(rabbitHost.key()).isEqualTo("RABBITMQ_HOST");
        assertThat(rabbitPort.key()).isEqualTo("RABBITMQ_PORT");
        assertThat(rabbitUsername.key()).isEqualTo("RABBITMQ_DEFAULT_USER");
        assertThat(rabbitPassword.key()).isEqualTo("RABBITMQ_DEFAULT_PASS");
        assertThat(redisHost.key()).isEqualTo("REDIS_HOST");
        assertThat(redisPort.key()).isEqualTo("REDIS_PORT");
    }

    private Placeholder extractAppPlaceholder(String dottedPath) throws Exception {
        return extractPlaceholder(APPLICATION_YAML_PATH, dottedPath);
    }

    private Placeholder extractPlaceholder(Path path, String dottedPath) throws Exception {
        Map<String, Object> root = loadYaml(path);
        Object rawValue = findByPath(root, dottedPath);
        Matcher matcher = APP_PLACEHOLDER_PATTERN.matcher(String.valueOf(rawValue));
        assertThat(matcher.matches())
                .as("Expected %s in %s to be a ${VAR} placeholder, got: %s", dottedPath, path, rawValue)
                .isTrue();
        return new Placeholder(matcher.group(1), matcher.group(2));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) throws Exception {
        return new Yaml().load(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private Object findByPath(Map<String, Object> root, String dottedPath) {
        Object current = root;
        for (String segment : dottedPath.split("\\.")) {
            assertThat(current).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(segment);
            assertThat(current).as(dottedPath).isNotNull();
        }
        return current;
    }

    private record Placeholder(String key, String defaultValue) {
    }
}
