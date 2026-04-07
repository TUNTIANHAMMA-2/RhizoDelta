package com.rhizodelta.infrastructure.security.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.BadCredentialsException;
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
            RETURN user.user_id AS userId,
                   user.username AS username,
                   user.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles
            """;
    private static final String FIND_USER_BY_ID_QUERY = """
            MATCH (user:UserAccount {user_id: $userId})
            RETURN user.user_id AS userId,
                   user.username AS username,
                   user.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles
            """;
    private static final String CREATE_USER_QUERY = """
            MERGE (user:UserAccount {username: $username})
            ON CREATE SET
              user.user_id = $userId,
              user.display_name = $displayName,
              user.password_hash = $passwordHash,
              user.roles = $roles,
              user.created_at = datetime()
            RETURN user.user_id AS userId,
                   user.username AS username,
                   user.display_name AS displayName,
                   user.password_hash AS passwordHash,
                   user.roles AS roles,
                   user.user_id = $userId AS created
            """;

    private final Neo4jClient neo4jClient;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey signingKey;

    public AuthController(
            Neo4jClient neo4jClient,
            PasswordEncoder passwordEncoder,
            @Value("${rhizodelta.jwt.secret}") String jwtSecret
    ) {
        this.neo4jClient = neo4jClient;
        this.passwordEncoder = passwordEncoder;
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/register")
    public ApiResponse<AuthSessionPayload> register(@RequestBody RegisterRequest request) {
        validateCredentials(request.username(), request.password());
        StoredUser user = createUser(request);
        return ApiResponse.ok(toSessionPayload(user, issueToken(user)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthSessionPayload> login(@RequestBody LoginRequest request) {
        validateCredentials(request.username(), request.password());
        StoredUser user = findUserByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BadCredentialsException("invalid username or password");
        }
        return ApiResponse.ok(toSessionPayload(user, issueToken(user)));
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
                        "roles", DEFAULT_ROLES
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
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }

    private static StoredUser toStoredUser(Map<String, Object> record) {
        return new StoredUser(
                record.get("userId").toString(),
                record.get("username").toString(),
                record.get("displayName").toString(),
                record.get("passwordHash").toString(),
                toStringList(record.get("roles"))
        );
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("roles must be a list");
        }
        return values.stream().map(Object::toString).toList();
    }

    private static AuthSessionPayload toSessionPayload(StoredUser user, String token) {
        return new AuthSessionPayload(token, toUserPayload(user));
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
            List<String> roles
    ) {
    }

    public record RegisterRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("display_name") String displayName
    ) {
    }

    public record LoginRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password
    ) {
    }

    public record AuthSessionPayload(
            @JsonProperty("token") String token,
            @JsonProperty("user") UserPayload user
    ) {
    }

    public record UserPayload(
            @JsonProperty("user_id") String userId,
            @JsonProperty("username") String username,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("roles") List<String> roles
    ) {
    }
}
