package com.rhizodelta.infrastructure.security.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.security.domain.UserStatus;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.security.service.RefreshTokenService;
import com.rhizodelta.infrastructure.security.service.TokenBlacklistService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.BadCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    private static final List<String> DEFAULT_ROLES = List.of("USER");
    private static final String FIND_USER_BY_USERNAME_QUERY = """
            MATCH (user:UserAccount {username: $username})
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN user.user_id AS userId,
                   user.username AS username,
                   profile.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles,
                   user.status AS status
            """;
    private static final String FIND_USER_BY_ID_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN user.user_id AS userId,
                   user.username AS username,
                   profile.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles,
                   user.status AS status
            """;
    private static final String CREATE_USER_QUERY = """
            MERGE (user:UserAccount {username: $username})
            ON CREATE SET
              user.user_id = $userId,
              user.password_hash = $passwordHash,
              user.roles = $roles,
              user.status = $statusValue,
              user.created_at = datetime()
            WITH user, user.user_id = $userId AS created
            FOREACH (_ IN CASE WHEN created THEN [1] ELSE [] END |
              MERGE (profile:UserProfile {user_id: user.user_id})
              ON CREATE SET
                profile.display_name = $displayName,
                profile.updated_at = datetime()
              MERGE (user)-[:HAS_PROFILE]->(profile)
            )
            WITH user, created
            OPTIONAL MATCH (user)-[:HAS_PROFILE]->(profile:UserProfile)
            RETURN user.user_id AS userId,
                   user.username AS username,
                   profile.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles,
                   created
            """;

    private final Neo4jClient neo4jClient;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey signingKey;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            Neo4jClient neo4jClient,
            PasswordEncoder passwordEncoder,
            @Value("${rhizodelta.jwt.secret}") String jwtSecret,
            TokenBlacklistService tokenBlacklistService,
            RefreshTokenService refreshTokenService
    ) {
        this.neo4jClient = neo4jClient;
        this.passwordEncoder = passwordEncoder;
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthSessionPayload> register(@RequestBody RegisterRequest request) {
        validateCredentials(request.username(), request.password());
        StoredUser user = createUser(request);
        String token = issueToken(user);
        String refreshToken = refreshTokenService.issue(user.userId());
        return ApiResponse.ok(toSessionPayload(user, token, refreshToken));
    }

    @PostMapping("/login")
    public ApiResponse<AuthSessionPayload> login(@RequestBody LoginRequest request) {
        validateCredentials(request.username(), request.password());
        StoredUser user = findUserByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BadCredentialsException("invalid username or password");
        }
        if (user.status() != null && !UserStatus.ACTIVE.name().equals(user.status())) {
            throw new BadCredentialsException("account is " + user.status().toLowerCase() + ", login denied");
        }
        String token = issueToken(user);
        String refreshToken = refreshTokenService.issue(user.userId());
        return ApiResponse.ok(toSessionPayload(user, token, refreshToken));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication, HttpServletRequest request) {
        AuthenticatedUser user = requireAuthenticatedUser(authentication);
        revokeCurrentAccessToken(request);
        refreshTokenService.revokeAllForUser(user.sub());
        return ApiResponse.ok(null);
    }

    private void revokeCurrentAccessToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return;
        }
        try {
            String token = header.substring(7);
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String jti = claims.getId();
            if (jti != null && claims.getExpiration() != null) {
                Duration remaining = Duration.between(Instant.now(), claims.getExpiration().toInstant());
                if (!remaining.isNegative() && !remaining.isZero()) {
                    tokenBlacklistService.revoke(jti, remaining);
                }
            }
        } catch (Exception e) {
            // Best-effort: if JWT parsing fails during logout, still proceed with refresh token revocation.
        }
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthSessionPayload> refresh(@RequestBody RefreshRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new IllegalArgumentException("refresh_token must not be blank");
        }
        String userId = refreshTokenService.consume(request.refreshToken());
        StoredUser user = findUserById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        if (!UserStatus.ACTIVE.name().equals(user.status())) {
            refreshTokenService.revokeAllForUser(userId);
            throw new BadCredentialsException("account is not active, refresh denied");
        }
        String token = issueToken(user);
        String newRefreshToken = refreshTokenService.issue(userId);
        return ApiResponse.ok(toSessionPayload(user, token, newRefreshToken));
    }

    @GetMapping("/me")
    public ApiResponse<UserPayload> me(Authentication authentication) {
        AuthenticatedUser user = requireAuthenticatedUser(authentication);
        StoredUser storedUser = findUserById(user.sub())
                .orElseThrow(() -> new NoSuchElementException("user not found"));
        return ApiResponse.ok(toUserPayload(storedUser));
    }

    private StoredUser createUser(RegisterRequest request) {
        String userId = UUID.randomUUID().toString();
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.username()
                : request.displayName().trim();
        String passwordHash = passwordEncoder.encode(request.password());
        Map<String, Object> result = neo4jClient.query(CREATE_USER_QUERY)
                .bindAll(Map.of(
                        "userId", userId,
                        "username", request.username().trim(),
                        "displayName", displayName,
                        "passwordHash", passwordHash,
                        "roles", DEFAULT_ROLES,
                        "statusValue", UserStatus.ACTIVE.name()
                ))
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalStateException("failed to create user"));
        if (!Boolean.TRUE.equals(result.get("created"))) {
            throw new IllegalArgumentException("username already exists");
        }
        return toStoredUser(result);
    }

    private Optional<StoredUser> findUserByUsername(String username) {
        return neo4jClient.query(FIND_USER_BY_USERNAME_QUERY)
                .bind(username.trim())
                .to("username")
                .fetch()
                .one()
                .map(AuthController::toStoredUser);
    }

    private Optional<StoredUser> findUserById(String userId) {
        return neo4jClient.query(FIND_USER_BY_ID_QUERY)
                .bind(userId)
                .to("userId")
                .fetch()
                .one()
                .map(AuthController::toStoredUser);
    }

    private String issueToken(StoredUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.userId())
                .claim("roles", user.roles())
                .claim("username", user.username())
                .claim("display_name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        return AuthenticatedUsers.require(authentication);
    }

    private static StoredUser toStoredUser(Map<String, Object> record) {
        String username = record.get("username").toString();
        Object displayNameRaw = record.get("displayName");
        Object statusRaw = record.get("status");
        return new StoredUser(
                record.get("userId").toString(),
                username,
                resolveDisplayName(displayNameRaw, username),
                record.get("passwordHash").toString(),
                toStringList(record.get("roles")),
                statusRaw == null ? null : statusRaw.toString()
        );
    }

    static String resolveDisplayName(Object profileDisplayName, String username) {
        if (profileDisplayName == null) {
            return username;
        }
        String text = profileDisplayName.toString();
        return text.isBlank() ? username : text;
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("roles must be a list");
        }
        return values.stream().map(Object::toString).toList();
    }

    private static AuthSessionPayload toSessionPayload(StoredUser user, String token, String refreshToken) {
        return new AuthSessionPayload(token, refreshToken, toUserPayload(user));
    }

    private static UserPayload toUserPayload(StoredUser user) {
        return new UserPayload(user.userId(), user.username(), user.displayName(), user.roles());
    }

    private static void validateCredentials(String username, String password) {
        requireText(username, "username");
        requireText(password, "password");
        if (password.trim().length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private record StoredUser(
            String userId,
            String username,
            String displayName,
            String passwordHash,
            List<String> roles,
            String status
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("display_name") String displayName
    ) {}

    public record LoginRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password
    ) {}

    public record RefreshRequest(
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    public record AuthSessionPayload(
            @JsonProperty("token") String token,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("user") UserPayload user
    ) {}

    public record UserPayload(
            @JsonProperty("user_id") String userId,
            @JsonProperty("username") String username,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("roles") List<String> roles
    ) {}
}
