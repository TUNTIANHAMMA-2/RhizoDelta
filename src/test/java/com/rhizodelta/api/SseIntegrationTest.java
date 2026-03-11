package com.rhizodelta.api;

import com.rhizodelta.consumer.PostConsumer;
import com.rhizodelta.domain.post.PostEventMessage;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class SseIntegrationTest {
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final String TEST_REQUEST_ID = "req-sse-1";
    private static final String TEST_AUTHOR_ID = "author-sse";
    private static final String TEST_CONTENT = "hello sse";
    private static final String TEST_SUBJECT = "test-operator";
    private static final String TEST_ROLE = "ROLE_TEST";

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
            .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PostConsumer postConsumer;

    @Autowired
    private Neo4jClient neo4jClient;

    @Value("${rhizodelta.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void cleanDatabase() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void shouldReceiveNodeCreatedEventAfterPostProcessing() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(streamUri())
                .header("Authorization", "Bearer " + buildToken(jwtSecret))
                .GET()
                .build();

        CompletableFuture<HttpResponse<java.io.InputStream>> responseFuture =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<java.io.InputStream> response = responseFuture.get(
                RESPONSE_TIMEOUT.toSeconds(),
                TimeUnit.SECONDS
        );
        assertThat(response.statusCode()).isEqualTo(200);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> dataLine = new AtomicReference<>();
        Thread readerThread = startSseReader(response, latch, dataLine);

        invokeProcessMessage(postConsumer, new PostEventMessage(
                TEST_REQUEST_ID,
                TEST_AUTHOR_ID,
                TEST_CONTENT,
                null,
                UUID.randomUUID().toString()
        ));

        assertThat(latch.await(EVENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        String nodeId = findNodeIdByRequestId(TEST_REQUEST_ID);
        assertThat(dataLine.get()).contains(nodeId);

        readerThread.interrupt();
    }

    private URI streamUri() {
        return URI.create("http://localhost:" + port + "/api/events/stream");
    }

    private static Thread startSseReader(
            HttpResponse<java.io.InputStream> response,
            CountDownLatch latch,
            AtomicReference<String> dataLine
    ) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                String eventName = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:") && "NODE_CREATED".equals(eventName)) {
                        dataLine.set(line.substring("data:".length()).trim());
                        latch.countDown();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
        return readerThread;
    }

    private static void invokeProcessMessage(PostConsumer postConsumer, PostEventMessage message) throws Exception {
        Method method = PostConsumer.class.getDeclaredMethod("processMessage", PostEventMessage.class);
        method.setAccessible(true);
        method.invoke(postConsumer, message);
    }

    private String findNodeIdByRequestId(String requestId) {
        return neo4jClient.query("""
                MATCH (post:Human_Post {request_id: $requestId})
                RETURN toString(post.node_id) AS nodeId
                """)
                .bind(requestId).to("requestId")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new IllegalStateException("node_id not found for request_id " + requestId));
    }

    private static String buildToken(String jwtSecret) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(TEST_SUBJECT)
                .claim("roles", java.util.List.of(TEST_ROLE))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key)
                .compact();
    }
}
