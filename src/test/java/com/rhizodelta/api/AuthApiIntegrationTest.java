package com.rhizodelta.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhizodelta.infrastructure.security.api.AuthController;
import com.rhizodelta.infrastructure.security.domain.UserStatus;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Autowired
    private ObjectMapper objectMapper;

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
        Map<String, Object> data = responseData(response);
        Map<String, Object> user = responseUser(response);
        Map<String, Object> storedUser = findUserRecord("alice");
        assertThat(data.get("token")).isNotNull();
        assertThat(user.get("username")).isEqualTo("alice");
        assertThat(user.get("display_name")).isEqualTo("Alice");
        assertThat(user.get("roles")).isEqualTo(List.of("USER"));
        assertThat(user.get("user_id")).isEqualTo(storedUser.get("userId"));
        assertThat(storedUser.get("status")).isEqualTo(UserStatus.ACTIVE.name());
        assertThat(storedUser.get("passwordHash").toString())
                .startsWith("$2")
                .isNotEqualTo("password123");
    }

    @Test
    void shouldIgnoreClientSuppliedUserIdOnRegister() {
        String clientUserId = "client-supplied-id";
        Map<String, Object> request = Map.of(
                "username", "ignored-id",
                "password", "password123",
                "display_name", "Ignored",
                "user_id", clientUserId
        );

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String actualUserId = responseUser(response).get("user_id").toString();
        UUID.fromString(actualUserId);
        assertThat(actualUserId).isNotEqualTo(clientUserId);
        assertThat(findUserRecord("ignored-id").get("userId")).isEqualTo(actualUserId);

        com.fasterxml.jackson.annotation.JsonIgnoreProperties annotation =
                AuthController.RegisterRequest.class.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonIgnoreProperties.class);
        assertThat(annotation)
                .as("RegisterRequest must declare @JsonIgnoreProperties(ignoreUnknown = true) "
                        + "so the ignore-unknown contract is local to the DTO (user-identity-hardening D2)")
                .isNotNull();
        assertThat(annotation.ignoreUnknown()).isTrue();
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
    void shouldKeepJwtPayloadShapeUnchangedAfterRegister() throws Exception {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of(
                        "username", "dave",
                        "password", "password123",
                        "display_name", "Dave"
                ),
                Map.class
        );

        Map<String, Object> claims = parseJwtPayload(responseData(response).get("token").toString());

        assertThat(claims.keySet()).containsExactlyInAnyOrder(
                "sub",
                "roles",
                "username",
                "display_name",
                "iat",
                "exp"
        );
        assertThat(claims.get("sub")).isEqualTo(responseUser(response).get("user_id"));
        assertThat(claims.get("roles")).isEqualTo(List.of("USER"));
        assertThat(claims.get("username")).isEqualTo("dave");
        assertThat(claims.get("display_name")).isEqualTo("Dave");
        assertThat(claims).doesNotContainKey("status");
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

    @Test
    void shouldReturnCurrentUserProfileWhenStoredRowHasNoStatus() {
        String token = registerUser("erin", "password123", "Erin");
        neo4jClient.query("""
                MATCH (user:UserAccount {username: $username})
                REMOVE user.status
                """)
                .bind("erin")
                .to("username")
                .run();

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
        assertThat(user.get("username")).isEqualTo("erin");
        assertThat(user.get("display_name")).isEqualTo("Erin");
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
        return responseData(response).get("token").toString();
    }

    private Map<String, Object> responseData(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private Map<String, Object> responseUser(ResponseEntity<Map> response) {
        return (Map<String, Object>) responseData(response).get("user");
    }

    private Map<String, Object> findUserRecord(String username) {
        return neo4jClient.query("""
                MATCH (user:UserAccount {username: $username})
                RETURN user.user_id AS userId,
                       user.password_hash AS passwordHash,
                       user.status AS status
                """)
                .bind(username)
                .to("username")
                .fetch()
                .one()
                .orElseThrow();
    }

    private Map<String, Object> parseJwtPayload(String token) throws Exception {
        String payload = token.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        return objectMapper.readValue(decoded, Map.class);
    }
}
