package com.rhizodelta.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Configuration
public class TestRestTemplateConfig {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TEST_SUBJECT = "test-operator";
    private static final List<String> TEST_ROLES = List.of("ADMIN");
    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    @Bean
    RestTemplateCustomizer testRestTemplateCustomizer(
            @Value("${rhizodelta.jwt.secret}") String jwtSecret) {
        String token = buildToken(jwtSecret);
        ClientHttpRequestInterceptor interceptor = bearerTokenInterceptor(token);
        return restTemplate -> restTemplate.getInterceptors().add(interceptor);
    }

    private static ClientHttpRequestInterceptor bearerTokenInterceptor(String token) {
        return (request, body, execution) -> {
            if (!request.getHeaders().containsKey(AUTHORIZATION_HEADER)) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        };
    }

    private static String buildToken(String jwtSecret) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(TEST_SUBJECT)
                .claim("roles", TEST_ROLES)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key)
                .compact();
    }
}
