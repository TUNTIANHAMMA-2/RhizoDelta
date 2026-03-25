package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class AuthApiIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldRegisterUserAndStoreHashedPassword() {
        Map<String, Object> request = Map.of(
                "username", "alice",
                "password", "password123",
                "display_name", "Alice"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        assertThat(data.get("token")).isNotNull();
        assertThat(user.get("username")).isEqualTo("alice");
        assertThat(user.get("display_name")).isEqualTo("Alice");
        assertThat(user.get("roles")).isEqualTo(List.of("USER"));
        assertThat(findPasswordHash("alice"))
                .startsWith("$2")
                .isNotEqualTo("password123");
    }

    @Test
    void shouldLoginWithRegisteredUser() {
        registerUser("bob", "password123", "Bob");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("username", "bob", "password", "password123"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        assertThat(data.get("token")).isNotNull();
        assertThat(user.get("username")).isEqualTo("bob");
    }

    @Test
    void shouldReturnCurrentUserProfileFromIssuedToken() {
        String token = registerUser("carol", "password123", "Carol");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/auth/me",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> user = (Map<String, Object>) response.getBody().get("data");
        assertThat(user.get("username")).isEqualTo("carol");
        assertThat(user.get("display_name")).isEqualTo("Carol");
        assertThat(user.get("roles")).isEqualTo(List.of("USER"));
    }

    private String registerUser(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of(
                        "username", username,
                        "password", password,
                        "display_name", displayName
                ),
                Map.class
        );
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return data.get("token").toString();
    }

    private String findPasswordHash(String username) {
        return neo4jClient.query("""
                MATCH (user:UserAccount {username: $username})
                RETURN user.password_hash AS passwordHash
                """)
                .bind(username)
                .to("username")
                .fetchAs(String.class)
                .one()
                .orElseThrow();
    }
}
