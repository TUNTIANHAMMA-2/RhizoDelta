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

    @Test
    void rejectsNonStringDisplayName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("display_name", 123);

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display_name");
    }

    @Test
    void rejectsOversizedDisplayName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("display_name", "x".repeat(200));

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display_name");
    }

    @Test
    void rejectsBlankDisplayName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("display_name", "   ");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display_name");
    }

    @Test
    void rejectsInvalidLanguageTag() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("language", "not_a_bcp47!!");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
    }

    @Test
    void rejectsInvalidTimezone() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("timezone", "Mars/Olympus_Mons");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    void acceptsValidTimezone() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("timezone", "Asia/Shanghai");

        UpdateUserProfileRequest req = new UpdateUserProfileRequest(raw);

        assertThat(req.stringOrNull("timezone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void rejectsThemeNotInEnum() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("theme", "neon-pink");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("theme");
    }

    @Test
    void normalizesThemeCaseInsensitively() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("theme", "DARK");

        UpdateUserProfileRequest req = new UpdateUserProfileRequest(raw);

        assertThat(req.stringOrNull("theme")).isEqualTo("dark");
    }

    @Test
    void rejectsNotificationPrefsAsString() {
        // 老代码的"非字符串 toString() 兜底"是 Major-5 的根因；
        // 这里锁定新行为：只接 JSON 对象。
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("notification_prefs", "{\"email\":true}");

        assertThatThrownBy(() -> new UpdateUserProfileRequest(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notification_prefs");
    }

    @Test
    void serializesNotificationPrefsMapToJson() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("email", true);
        prefs.put("push", false);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("notification_prefs", prefs);

        UpdateUserProfileRequest req = new UpdateUserProfileRequest(raw);

        assertThat(req.stringOrNull("notification_prefs"))
                .isEqualTo("{\"email\":true,\"push\":false}");
    }

    @Test
    void preservesExplicitNullForClearSemantics() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("language", null);

        UpdateUserProfileRequest req = new UpdateUserProfileRequest(raw);

        assertThat(req.isPresent("language")).isTrue();
        assertThat(req.stringOrNull("language")).isNull();
    }
}
