package com.rhizodelta.infrastructure.user.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the SSRF / phishing guard: the generic profile PUT must reject any
 * write to {@code avatar_url}. Avatar updates only flow through the dedicated
 * /me/avatar endpoint that runs magic-byte validation.
 */
class UpdateUserProfileRequestUnitTest {

    @Test
    void rejectsAvatarUrlWriteWithExplicitValue() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("avatar_url", "http://attacker.example/x.gif");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avatar_url");
    }

    @Test
    void rejectsAvatarUrlEvenWhenExplicitlyNull() {
        // 即便客户端只是想清空 avatar_url 字段，也必须走 /me/avatar，
        // 否则成为绕过：构造器应一视同仁拒绝。
        Map<String, Object> raw = new HashMap<>();
        raw.put("avatar_url", null);

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avatar_url");
    }

    @Test
    void acceptsBenignFields() {
        Map<String, Object> raw = Map.of(
                "display_name", "Alice",
                "language", "en",
                "theme", "dark"
        );

        UpdateUserProfileRequest req = new UpdateUserProfileRequest(raw);

        assertThat(req.presentMutableFields()).containsExactlyInAnyOrder(
                "display_name", "language", "theme");
    }

    @Test
    void mutableFieldsDoesNotIncludeAvatarUrl() {
        // Defense in depth: even if the constructor guard is bypassed,
        // applyUpdate iterates MUTABLE_FIELDS and would skip avatar_url.
        assertThat(UpdateUserProfileRequest.MUTABLE_FIELDS).doesNotContain("avatar_url");
        assertThat(UpdateUserProfileRequest.READ_ONLY_FIELDS).contains("avatar_url");
    }
}
