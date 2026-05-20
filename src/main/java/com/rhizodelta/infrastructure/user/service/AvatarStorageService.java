package com.rhizodelta.infrastructure.user.service;

import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AvatarStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvatarStorageService.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_SIZE_BYTES = 2 * 1024 * 1024; // 2MB
    private static final int PRESIGNED_URL_EXPIRY_MINUTES = 60;

    private final MinioClient minioClient;
    private final String bucket;
    private final Path localStoragePath;
    private final boolean enabled;

    public AvatarStorageService(
            ObjectProvider<MinioClient> minioClientProvider,
            @Value("${rhizodelta.minio.bucket:rhizodelta-avatars}") String bucket,
            @Value("${rhizodelta.minio.enabled:false}") boolean enabled,
            @Value("${rhizodelta.avatar.local-storage-path:./data/avatars}") String localStoragePath
    ) {
        this.minioClient = minioClientProvider.getIfAvailable();
        this.bucket = bucket;
        this.enabled = enabled && this.minioClient != null;
        this.localStoragePath = Path.of(localStoragePath);
        if (this.enabled) {
            ensureBucket();
        } else {
            ensureLocalStorageDir();
        }
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to ensure MinIO bucket '{}': {}", bucket, e.getMessage());
        }
    }

    private void ensureLocalStorageDir() {
        try {
            Files.createDirectories(localStoragePath);
        } catch (Exception e) {
            LOGGER.warn("Failed to create local avatar storage directory '{}': {}", localStoragePath, e.getMessage());
        }
    }

    /**
     * 校验上传文件的 content-type、大小，以及通过 magic bytes 验证内容真实类型。
     *
     * <p>仅检查 content-type 是不安全的 —— 客户端可任意伪造。读取前 12 字节匹配
     * JPEG / PNG / WebP 的标准签名能识别绝大多数伪装攻击。
     *
     * @param contentType HTTP Content-Type 头部
     * @param size 文件大小（字节）
     * @param content 完整文件字节，用于 magic bytes 检测；至少 12 字节
     * @throws IllegalArgumentException 任何一项校验失败
     */
    public void validateFile(String contentType, long size, byte[] content) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported file type. Allowed: JPEG, PNG, WebP");
        }
        if (size > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("file size exceeds 2MB limit");
        }
        if (content == null || content.length < 12) {
            throw new IllegalArgumentException("file content too small to validate magic bytes");
        }
        DetectedType detected = detectMagicBytes(content);
        if (detected == DetectedType.UNKNOWN) {
            throw new IllegalArgumentException("file content does not match a supported image format");
        }
        if (!detected.contentType.equals(contentType)) {
            throw new IllegalArgumentException(
                    "content-type '" + contentType + "' does not match detected magic bytes (" + detected.contentType + ")");
        }
    }

    /**
     * 把字节内容写入对象存储或本地 fallback 路径，返回相对对象路径。
     */
    public String upload(String userId, byte[] content, String contentType) throws IOException {
        // Always remove any pre-existing avatar with a different extension before writing
        // a new one — otherwise switching from PNG to JPEG would leave the PNG orphaned.
        try {
            delete(userId);
        } catch (Exception e) {
            LOGGER.debug("Pre-upload cleanup ignored for user {}: {}", userId, e.getMessage());
        }
        String ext = contentTypeToExtension(contentType);
        String objectPath = "avatars/" + userId + "/avatar." + ext;
        if (enabled) {
            try {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectPath)
                                .stream(new ByteArrayInputStream(content), content.length, -1)
                                .contentType(contentType)
                                .build()
                );
            } catch (Exception e) {
                throw new IOException("MinIO upload failed: " + e.getMessage(), e);
            }
        } else {
            Path targetPath = localStoragePath.resolve(objectPath);
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return objectPath;
    }

    public void delete(String userId) throws IOException {
        if (enabled) {
            for (String ext : new String[]{"jpg", "jpeg", "png", "webp"}) {
                String objectPath = "avatars/" + userId + "/avatar." + ext;
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectPath).build());
                } catch (Exception e) {
                    LOGGER.debug("No avatar to delete at path {}: {}", objectPath, e.getMessage());
                }
            }
        } else {
            for (String ext : new String[]{"jpg", "jpeg", "png", "webp"}) {
                Files.deleteIfExists(localStoragePath.resolve("avatars/" + userId + "/avatar." + ext));
            }
        }
    }

    public String getPresignedUrl(String objectPath) {
        if (objectPath == null) return null;
        if (!isAllowedAvatarObjectPath(objectPath)) {
            LOGGER.debug("Rejected invalid avatar object path: {}", objectPath);
            return null;
        }
        if (enabled && minioClient != null) {
            try {
                return minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucket)
                                .object(objectPath)
                                .expiry(PRESIGNED_URL_EXPIRY_MINUTES, TimeUnit.MINUTES)
                                .build()
                );
            } catch (Exception e) {
                LOGGER.debug("Failed to generate presigned URL for {}: {}", objectPath, e.getMessage());
                return null;
            }
        }
        return "/api/files/avatars/" + objectPath;
    }

    private static boolean isAllowedAvatarObjectPath(String objectPath) {
        return objectPath.startsWith("avatars/")
                && !objectPath.startsWith("/")
                && !objectPath.contains("..")
                && !objectPath.contains("//")
                && !objectPath.startsWith("http://")
                && !objectPath.startsWith("https://");
    }

    private static String contentTypeToExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private DetectedType detectMagicBytes(byte[] content) {
        // JPEG: FF D8 FF
        if ((content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return DetectedType.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((content[0] & 0xFF) == 0x89
                && content[1] == 'P' && content[2] == 'N' && content[3] == 'G'
                && (content[4] & 0xFF) == 0x0D && (content[5] & 0xFF) == 0x0A
                && (content[6] & 0xFF) == 0x1A && (content[7] & 0xFF) == 0x0A) {
            return DetectedType.PNG;
        }
        // WebP: 52 49 46 46 ?? ?? ?? ?? 57 45 42 50  ("RIFF" .... "WEBP")
        if (content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return DetectedType.WEBP;
        }
        return DetectedType.UNKNOWN;
    }

    private enum DetectedType {
        JPEG("image/jpeg"),
        PNG("image/png"),
        WEBP("image/webp"),
        UNKNOWN("");

        final String contentType;

        DetectedType(String contentType) {
            this.contentType = contentType;
        }
    }
}
