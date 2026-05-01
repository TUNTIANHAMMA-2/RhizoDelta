package com.rhizodelta.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.6 — Avatar 端到端：上传 → profile.avatar_url 写回 Neo4j → /me/profile 返回 URL → delete → URL 清空。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "rhizodelta.minio.enabled=false",
        "rhizodelta.avatar.local-storage-path=/tmp/rhizodelta-avatar-it"
})
class AvatarLifecycleIntegrationTest {

    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 0
    };

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
    void cleanDatabase() throws Exception {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        Path local = Path.of("/tmp/rhizodelta-avatar-it");
        if (Files.exists(local)) {
            try (var stream = Files.walk(local)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void uploadPersistsAvatarUrlOnProfile() {
        String token = registerUser("alice", "password123", "Alice");

        ResponseEntity<Map> uploadResp = uploadAvatar(token, JPEG_BYTES, "image/jpeg");
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> profile = (Map<String, Object>) uploadResp.getBody().get("data");
        assertThat(profile.get("avatar_url")).asString().isNotEmpty();

        // 单独 GET profile —— avatar_url 必须仍然存在（说明已经写回 Neo4j）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> meResp = restTemplate.exchange(
                "/api/users/me/profile", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> meProfile = (Map<String, Object>) meResp.getBody().get("data");
        assertThat(meProfile.get("avatar_url")).asString().isNotEmpty();
    }

    @Test
    void deleteClearsAvatarUrlOnProfile() {
        String token = registerUser("bob", "password123", "Bob");
        uploadAvatar(token, JPEG_BYTES, "image/jpeg");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> deleteResp = restTemplate.exchange(
                "/api/users/me/avatar", HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> meResp = restTemplate.exchange(
                "/api/users/me/profile", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> meProfile = (Map<String, Object>) meResp.getBody().get("data");
        assertThat(meProfile.get("avatar_url")).isNull();
    }

    @Test
    void uploadRejectsForgedContentType() {
        String token = registerUser("carol", "password123", "Carol");
        // PNG bytes claimed as JPEG
        byte[] pngBytes = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
                0, 0, 0, 0, 'p', 'a', 'd', 'd'
        };
        ResponseEntity<Map> resp = uploadAvatar(token, pngBytes, "image/jpeg");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String registerUser(String username, String password, String displayName) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", password, "display_name", displayName),
                Map.class
        );
        return ((Map<String, Object>) response.getBody().get("data")).get("token").toString();
    }

    private ResponseEntity<Map> uploadAvatar(String token, byte[] content, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "avatar.bin";
            }

            @Override
            public long contentLength() {
                return content.length;
            }
        });

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        org.springframework.http.HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return "avatar.bin";
                    }

                    @Override
                    public long contentLength() {
                        return content.length;
                    }
                },
                partHeaders
        );
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", filePart);

        return restTemplate.exchange(
                "/api/users/me/avatar",
                HttpMethod.PUT,
                new HttpEntity<>(multipart, headers),
                Map.class
        );
    }
}
