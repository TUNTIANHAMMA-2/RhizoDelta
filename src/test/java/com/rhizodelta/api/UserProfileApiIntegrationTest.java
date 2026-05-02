package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
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
class UserProfileApiIntegrationTest {
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

    @LocalServerPort
    private int port;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldReturnProfileForAuthenticatedUser() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/profile");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload.get("user_id")).isEqualTo(findUserId("alice"));
        assertThat(payload.get("display_name")).isEqualTo("Alice");
        assertThat(payload.get("updated_at")).isNotNull();
        assertThat(payload.get("avatar_url")).isNull();
        assertThat(payload.get("language")).isNull();
    }

    @Test
    void shouldFallBackToUsernameWhenProfileEdgeMissing() {
        String token = registerUser("bob", "password123", "Bobby");
        neo4jClient.query("""
                MATCH (:UserAccount {username: 'bob'})-[edge:HAS_PROFILE]->(profile:UserProfile)
                DELETE edge
                DETACH DELETE profile
                """).run();

        ResponseEntity<Map> response = authorizedGet(token, "/api/users/me/profile");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload.get("display_name")).isEqualTo("bob");
        assertThat(payload.get("avatar_url")).isNull();
        assertThat(payload.get("updated_at")).isNull();
    }

    @Test
    void shouldUpdateOnlySpecifiedFieldsOnPut() {
        String token = registerUser("carol", "password123", "Carol");
        Map<String, Object> firstBody = Map.of("theme", "dark", "language", "en");
        ResponseEntity<Map> firstPut = authorizedPut(token, "/api/users/me/profile", firstBody);
        assertThat(firstPut.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstUpdatedAt = responseData(firstPut).get("updated_at").toString();

        Map<String, Object> secondBody = Map.of("display_name", "Carol-New");
        ResponseEntity<Map> secondPut = authorizedPut(token, "/api/users/me/profile", secondBody);

        assertThat(secondPut.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(secondPut);
        assertThat(payload.get("display_name")).isEqualTo("Carol-New");
        assertThat(payload.get("theme"))
                .as("theme set by the first PUT must survive the second PUT that did not mention it")
                .isEqualTo("dark");
        assertThat(payload.get("language"))
                .as("language set by the first PUT must survive too")
                .isEqualTo("en");
        assertThat(payload.get("updated_at").toString())
                .as("updated_at must advance on every successful PUT")
                .isNotEqualTo(firstUpdatedAt);
    }

    @Test
    void shouldClearFieldOnExplicitNull() {
        String token = registerUser("dave", "password123", "Dave");
        authorizedPut(token, "/api/users/me/profile", Map.of("language", "en"));

        Map<String, Object> clearing = new HashMap<>();
        clearing.put("language", null);
        ResponseEntity<Map> response = authorizedPut(token, "/api/users/me/profile", clearing);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseData(response).get("language"))
                .as("explicit null in body must clear the stored field")
                .isNull();
    }

    /**
     * SSRF / phishing guard: avatar_url is read-only on the generic profile PUT —
     * users must not be able to inject arbitrary external URLs that other users
     * then fetch via &lt;img src&gt;. The dedicated PUT /me/avatar endpoint
     * runs magic-byte validation and writes the storage path; this PUT must reject
     * any avatar_url field outright.
     */
    @Test
    void shouldRejectAvatarUrlOnGenericProfilePut() {
        String token = registerUser("eve", "password123", "Eve");
        ResponseEntity<Map> response = authorizedPut(token, "/api/users/me/profile",
                Map.of("avatar_url", "http://attacker.example/track.gif"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReject400WhenNoMutableFieldProvided() {
        String token = registerUser("erin", "password123", "Erin");

        ResponseEntity<Map> response = authorizedPut(token, "/api/users/me/profile", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldIgnoreUnknownFieldsLikeUserIdOnPut() {
        String token = registerUser("frank", "password123", "Frank");
        Map<String, Object> body = new HashMap<>();
        body.put("display_name", "Franklin");
        body.put("user_id", "attacker-supplied-id");

        ResponseEntity<Map> response = authorizedPut(token, "/api/users/me/profile", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload.get("display_name")).isEqualTo("Franklin");
        assertThat(payload.get("user_id"))
                .as("user_id is server-derived from JWT sub; request body cannot override it")
                .isEqualTo(findUserId("frank"))
                .isNotEqualTo("attacker-supplied-id");
    }

    @Test
    void shouldReturnPublicProfileForAuthenticatedCaller() {
        registerUser("grace", "password123", "Grace");
        String viewerToken = registerUser("harry", "password123", "Harry");

        ResponseEntity<Map> response = authorizedGet(viewerToken, "/api/users/" + findUserId("grace") + "/profile");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload).containsEntry("user_id", findUserId("grace"));
        assertThat(payload).containsEntry("username", "grace");
        assertThat(payload).containsEntry("display_name", "Grace");
        assertThat(payload.get("avatar_url")).isNull();
        assertThat(payload).doesNotContainKeys("roles", "language", "timezone", "theme", "notification_prefs");
    }

    @Test
    void shouldReturnPublicProfileWithUsernameFallbackWhenProfileMissing() {
        registerUser("ivy", "password123", "Ivy");
        String viewerToken = registerUser("jane", "password123", "Jane");
        neo4jClient.query("""
                MATCH (:UserAccount {username: 'ivy'})-[edge:HAS_PROFILE]->(profile:UserProfile)
                DELETE edge
                DETACH DELETE profile
                """).run();

        ResponseEntity<Map> response = authorizedGet(viewerToken, "/api/users/" + findUserId("ivy") + "/profile");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload.get("display_name")).isEqualTo("ivy");
        assertThat(payload.get("avatar_url")).isNull();
    }

    @Test
    void shouldMaskSuspendedOrDeletedPublicProfile() {
        registerUser("kate", "password123", "Kate");
        String viewerToken = registerUser("liam", "password123", "Liam");
        neo4jClient.query("""
                MATCH (user:UserAccount {username: 'kate'})
                SET user.status = 'SUSPENDED'
                """).run();

        ResponseEntity<Map> response = authorizedGet(viewerToken, "/api/users/" + findUserId("kate") + "/profile");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = responseData(response);
        assertThat(payload).containsEntry("user_id", findUserId("kate"));
        assertThat(payload).containsEntry("status", "UNAVAILABLE");
        assertThat(payload).doesNotContainKeys("username", "display_name", "avatar_url");
    }

    @Test
    void shouldReject401ForPublicProfileWithoutJwt() {
        String targetUserId = registerAndGetUserId("mona", "password123", "Mona");

        ResponseEntity<Map> response = new TestRestTemplate()
                .getForEntity(baseUrl("/api/users/" + targetUserId + "/profile"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String registerUser(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", password, "display_name", displayName),
                Map.class
        );
        return ((Map<String, Object>) response.getBody().get("data")).get("token").toString();
    }

    private ResponseEntity<Map> authorizedGet(String token, String uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> authorizedPut(String token, String uri, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(uri, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
    }

    private Map<String, Object> responseData(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String findUserId(String username) {
        return neo4jClient.query("MATCH (u:UserAccount {username: $username}) RETURN u.user_id AS userId")
                .bind(username).to("username")
                .fetchAs(String.class)
                .one()
                .orElseThrow();
    }

    private String registerAndGetUserId(String username, String password, String displayName) {
        registerUser(username, password, displayName);
        return findUserId(username);
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }
}
