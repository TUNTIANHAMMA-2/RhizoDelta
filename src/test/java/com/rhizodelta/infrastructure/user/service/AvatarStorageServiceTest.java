package com.rhizodelta.infrastructure.user.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 13.6 — Avatar storage. 验证本地 fallback 下的存取与 magic-byte 校验。
 *
 * <p>UserProfile 的 avatar_url 联动写回由 {@code AvatarLifecycleIntegrationTest} 端到端覆盖。
 */
class AvatarStorageServiceTest {

    private static final byte[] JPEG_MAGIC = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0, 0, 0, 0, 0, 0, 0, 0, 'p', 'a', 'd', 'd', 'i', 'n', 'g'
    };

    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 'P', 'N', 'G',
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
            0, 0, 0, 0, 'p', 'a', 'd'
    };

    private static final byte[] WEBP_MAGIC = new byte[]{
            'R', 'I', 'F', 'F',
            0, 0, 0, 0,
            'W', 'E', 'B', 'P',
            0, 0, 0, 0
    };

    @TempDir
    Path tempDir;

    private AvatarStorageService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<MinioClient> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        service = new AvatarStorageService(provider, "test-bucket", false, tempDir.toString());
    }

    @Test
    void uploadCreatesLocalFile() throws Exception {
        String path = service.upload("user-1", JPEG_MAGIC, "image/jpeg");

        assertThat(path).isEqualTo("avatars/user-1/avatar.jpg");
        assertThat(Files.exists(tempDir.resolve("avatars/user-1/avatar.jpg"))).isTrue();
    }

    @Test
    void uploadOfNewExtensionRemovesPriorAvatar() throws Exception {
        service.upload("user-2", JPEG_MAGIC, "image/jpeg");
        assertThat(Files.exists(tempDir.resolve("avatars/user-2/avatar.jpg"))).isTrue();

        service.upload("user-2", PNG_MAGIC, "image/png");
        assertThat(Files.exists(tempDir.resolve("avatars/user-2/avatar.png"))).isTrue();
        // 旧扩展名文件应被自动清理 —— 不能留下两份头像。
        assertThat(Files.exists(tempDir.resolve("avatars/user-2/avatar.jpg"))).isFalse();
    }

    @Test
    void presignedUrlFallsBackToLocalPath() {
        String url = service.getPresignedUrl("avatars/user-1/avatar.jpg");
        assertThat(url).isEqualTo("/api/files/avatars/avatars/user-1/avatar.jpg");
    }

    @Test
    void presignedUrlReturnsNullForNullInput() {
        assertThat(service.getPresignedUrl(null)).isNull();
    }

    @Test
    void deleteRemovesAllExtensions() throws Exception {
        service.upload("user-2", JPEG_MAGIC, "image/jpeg");
        assertThat(Files.exists(tempDir.resolve("avatars/user-2/avatar.jpg"))).isTrue();

        service.delete("user-2");
        assertThat(Files.exists(tempDir.resolve("avatars/user-2/avatar.jpg"))).isFalse();
    }

    @Test
    void validateFileRejectsInvalidContentType() {
        assertThatThrownBy(() -> service.validateFile("application/pdf", 1000, JPEG_MAGIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported file type");
    }

    @Test
    void validateFileRejectsOversizedFile() {
        assertThatThrownBy(() -> service.validateFile("image/jpeg", 3 * 1024 * 1024, JPEG_MAGIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2MB");
    }

    @Test
    void validateFileRejectsForgedContentType() {
        // PNG 字节但声称 image/jpeg —— 必须拒绝
        assertThatThrownBy(() -> service.validateFile("image/jpeg", PNG_MAGIC.length, PNG_MAGIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validateFileRejectsUnknownMagicBytes() {
        byte[] junk = "not really an image at all....".getBytes();
        assertThatThrownBy(() -> service.validateFile("image/jpeg", junk.length, junk))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validateFileRejectsTooSmallContent() {
        byte[] tiny = new byte[]{1, 2};
        assertThatThrownBy(() -> service.validateFile("image/jpeg", tiny.length, tiny))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void validateFileAcceptsValidJpegPngWebp() {
        service.validateFile("image/jpeg", JPEG_MAGIC.length, JPEG_MAGIC);
        service.validateFile("image/png", PNG_MAGIC.length, PNG_MAGIC);
        service.validateFile("image/webp", WEBP_MAGIC.length, WEBP_MAGIC);
    }
}
