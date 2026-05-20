package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.5 — Refresh token rotation, reuse detection, and revoke-all-on-reuse cascade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class RefreshTokenIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
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
    void registrationIssuesRefreshToken() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", "alice", "password", "password123", "display_name", "Alice"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("token")).isNotNull();
        assertThat(data.get("refresh_token")).isNotNull();
    }

    @Test
    void refreshRotatesAndIssuesNewPair() {
        String refreshToken = registerAndGetRefreshToken("bob", "password123", "Bob");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", refreshToken),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        String newToken = data.get("token").toString();
        String newRefreshToken = data.get("refresh_token").toString();
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // New access token must be usable
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(newToken);
        ResponseEntity<Map> meResponse = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 关键安全断言：当老 token 被再次使用，detect-reuse → revokeAllForUser 触发，
     * 第一次轮换返回的"新" refresh token 也应当无效。否则攻击者可以用偷得的 token
     * 链继续刷新。
     */
    @Test
    void reuseDetectionRevokesAllSiblingTokens() {
        String originalRefresh = registerAndGetRefreshToken("carol", "password123", "Carol");

        ResponseEntity<Map> firstRefresh = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", originalRefresh),
                Map.class
        );
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = ((Map<String, Object>) firstRefresh.getBody().get("data"))
                .get("refresh_token").toString();

        // 模拟攻击者：用已经被使用的 originalRefresh 重放
        ResponseEntity<Map> reuseAttempt = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", originalRefresh),
                Map.class
        );
        assertThat(reuseAttempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 此时 victim 持有的 newRefresh 也必须失效
        ResponseEntity<Map> victimRetry = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", newRefresh),
                Map.class
        );
        assertThat(victimRetry.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankRefreshTokenIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", ""),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidRefreshTokenIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", "not-a-valid-token-at-all"),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void suspendedUserRefreshReturnsUnauthorizedAndInvalidatesConsumedToken() {
        String refreshToken = registerAndGetRefreshToken("erin", "password123", "Erin");
        neo4jClient.query("""
                MATCH (user:UserAccount {username: $username})
                SET user.status = 'SUSPENDED'
                """)
                .bindAll(Map.of("username", "erin"))
                .run();

        ResponseEntity<Map> suspendedRefresh = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", refreshToken),
                Map.class
        );
        assertThat(suspendedRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> retryWithConsumedToken = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", refreshToken),
                Map.class
        );
        assertThat(retryWithConsumedToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void logoutInvalidatesAllRefreshTokens() {
        // 注册一次同时拿到 access token 与 refresh token，模拟真实用户的退出。
        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", "dave", "password", "password123", "display_name", "Dave"),
                Map.class
        );
        Map<String, Object> data = (Map<String, Object>) registerResponse.getBody().get("data");
        String token = data.get("token").toString();
        String refreshToken = data.get("refresh_token").toString();

        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.setBearerAuth(token);
        ResponseEntity<Map> logoutResponse = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), Map.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> refreshResponse = restTemplate.postForEntity(
                "/api/auth/refresh",
                Map.of("refresh_token", refreshToken),
                Map.class
        );
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String registerUser(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", password, "display_name", displayName),
                Map.class
        );
        return ((Map<String, Object>) response.getBody().get("data")).get("token").toString();
    }

    private String registerAndGetRefreshToken(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", password, "display_name", displayName),
                Map.class
        );
        return ((Map<String, Object>) response.getBody().get("data")).get("refresh_token").toString();
    }
}
