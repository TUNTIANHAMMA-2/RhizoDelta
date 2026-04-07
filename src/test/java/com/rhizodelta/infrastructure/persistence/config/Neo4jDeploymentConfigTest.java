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

    private Placeholder extractAppPlaceholder(String dottedPath) throws Exception {
        Map<String, Object> root = loadYaml(APPLICATION_YAML_PATH);
        Object rawValue = findByPath(root, dottedPath);
        Matcher matcher = APP_PLACEHOLDER_PATTERN.matcher(String.valueOf(rawValue));
        assertThat(matcher.matches())
                .as("Expected %s to be a ${VAR} placeholder, got: %s", dottedPath, rawValue)
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
