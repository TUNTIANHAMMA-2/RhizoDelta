package com.rhizodelta.infrastructure.security.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.domain.UserStatus;
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

/**
 * 提供注册、登录与当前用户信息接口。
 *
 * <p>该控制器负责管理用户账户的创建与认证会话签发，
 * 并通过 JWT 把认证结果返回给调用方。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>注册会写 Neo4j 中的 {@code UserAccount} 节点。</li>
 *   <li>登录和注册都会签发新的 JWT。</li>
 *   <li>密码会在写库前经由 {@link PasswordEncoder} 加密。</li>
 * </ul>
 */
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
              user.status = $statusValue,
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

    /**
     * 注册一个新用户并返回登录态。
     *
     * <p>若用户名已存在，该接口会直接失败，不会悄悄覆盖原账户。
     */
    @PostMapping("/register")
    public ApiResponse<AuthSessionPayload> register(@RequestBody RegisterRequest request) {
        validateCredentials(request.username(), request.password());
        StoredUser user = createUser(request);
        return ApiResponse.ok(toSessionPayload(user, issueToken(user)));
    }

    /**
     * 使用用户名和密码登录并返回登录态。
     */
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

    /**
     * 返回当前认证用户信息。
     */
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

    /**
     * 表示注册请求。
     *
     * <p>显式忽略未知字段，避免依赖全局 {@code ObjectMapper} 配置；
     * 这样即使未来某处启用了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
     * 注册端点仍然会安全地丢弃客户端提交的 {@code user_id} 等字段，
     * 而不会升级为 400。详见 {@code user-identity-hardening} D2。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("display_name") String displayName
    ) {
    }

    /**
     * 表示登录请求。
     */
    public record LoginRequest(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password
    ) {
    }

    /**
     * 表示认证会话响应。
     */
    public record AuthSessionPayload(
            @JsonProperty("token") String token,
            @JsonProperty("user") UserPayload user
    ) {
    }

    /**
     * 表示当前用户的对外视图。
     */
    public record UserPayload(
            @JsonProperty("user_id") String userId,
            @JsonProperty("username") String username,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("roles") List<String> roles
    ) {
    }
}
