package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * S3 分片上传集群无状态自包含签名 Token 工具
 * 支持将目标路径 cleanPath 与云端 rawS3UploadId 安全签名为防篡改的 Base64Url 字符串，
 * 破除多 Pod / K8s 集群扩缩容环境下的本地内存 Session 黏性与集群漂移故障
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class S3ChunkTokenUtil {

    private static final String SEPARATOR = "::";

    private S3ChunkTokenUtil() {
    }

    /**
     * 将 cleanPath 与 rawS3UploadId 编码为带有 HMAC-SHA256 防篡改签名的无状态 Token
     * 规范格式：Base64Url(cleanPath + "::" + rawS3UploadId + "::" + hmac)
     *
     * @param cleanPath     清洗后的存储相对路径
     * @param rawS3UploadId S3 底层返回的原生 uploadId
     * @param secretKey     用于 HMAC-SHA256 签名的密钥
     * @return URL 安全的 Base64 无状态 Token 字符串
     */
    public static String encode(String cleanPath, String rawS3UploadId, String secretKey) {
        if (cleanPath == null || cleanPath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "编码分片 Token 失败：目标路径不能为空");
        }
        if (rawS3UploadId == null || rawS3UploadId.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "编码分片 Token 失败：S3 原生 uploadId 不能为空");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "编码分片 Token 失败：签名密钥不能为空");
        }

        String dataToSign = cleanPath + SEPARATOR + rawS3UploadId;
        String hmac = StorageSecurityUtil.hmacSha256(dataToSign, secretKey);
        String payload = dataToSign + SEPARATOR + hmac;

        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码并验证无状态 Token 的签名完整性
     *
     * @param token     待解码的 Base64Url Token
     * @param secretKey 用于 HMAC-SHA256 验签的密钥
     * @return 包含 cleanPath 与 rawS3UploadId 的 Token 模型
     * @throws StorageException 若 Token 为空、格式非法或签名已被篡改
     */
    public static S3ChunkToken decode(String token, String secretKey) {
        if (token == null || token.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传会话标识 uploadId 不能为空");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "解码分片 Token 失败：验签密钥不能为空");
        }

        String payload;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token.trim());
            payload = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new StorageException(StorageResultCode.PRESIGNED_URL_INVALID, "非法或损坏的分片上传 Token (Base64 解码失败): " + token, e);
        }

        String[] parts = payload.split(SEPARATOR, -1);
        if (parts.length != 3) {
            throw new StorageException(StorageResultCode.PRESIGNED_URL_INVALID, "分片上传 Token 格式不符合三段式规范: " + token);
        }

        String path = parts[0];
        String rawUploadId = parts[1];
        String hmac = parts[2];

        if (path.isBlank() || rawUploadId.isBlank() || hmac.isBlank()) {
            throw new StorageException(StorageResultCode.PRESIGNED_URL_INVALID, "分片上传 Token 载荷字段缺失");
        }

        String dataToSign = path + SEPARATOR + rawUploadId;
        if (!StorageSecurityUtil.verifyHmacSha256(dataToSign, secretKey, hmac)) {
            throw new StorageException(StorageResultCode.PRESIGNED_URL_INVALID, "分片上传 Token 签名无效或已被篡改");
        }

        return new S3ChunkToken(path, rawUploadId);
    }

    /**
     * 自包含 Token 解码结果
     *
     * @param path        已清洗的目标存储路径
     * @param rawUploadId S3 原生 uploadId
     */
    public record S3ChunkToken(String path, String rawUploadId) {
    }
}
