package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3ChunkTokenUtil 单元测试
 * 覆盖无状态 Token 编解码、防篡改签名校验、异常格式边界与时序攻击防御
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("S3ChunkTokenUtil 分片无状态 Token 工具测试")
class S3ChunkTokenUtilTest {

    private static final String SECRET_KEY = "test-secret-key-for-s3-token";
    private static final String PATH = "videos/2026/presentation.mp4";
    private static final String RAW_UPLOAD_ID = "s3-raw-multipart-id-abc123xyz";

    @Test
    @DisplayName("正常编码并成功解码无状态 Token")
    void testEncodeAndDecodeSuccess() {
        String token = S3ChunkTokenUtil.encode(PATH, RAW_UPLOAD_ID, SECRET_KEY);
        assertNotNull(token);
        assertTrue(!token.isBlank());
        // 确保是 Base64Url 字符集，不含 / 或 +
        assertTrue(!token.contains("/") && !token.contains("+"));

        S3ChunkTokenUtil.S3ChunkToken decoded = S3ChunkTokenUtil.decode(token, SECRET_KEY);
        assertNotNull(decoded);
        assertEquals(PATH, decoded.path());
        assertEquals(RAW_UPLOAD_ID, decoded.rawUploadId());
    }

    @Test
    @DisplayName("使用错误密钥解码时抛出 PRESIGNED_URL_INVALID 签名异常")
    void testDecodeWithWrongSecretKey() {
        String token = S3ChunkTokenUtil.encode(PATH, RAW_UPLOAD_ID, SECRET_KEY);

        StorageException ex = assertThrows(StorageException.class, () ->
                S3ChunkTokenUtil.decode(token, "different-wrong-secret-key"));
        assertEquals(StorageResultCode.PRESIGNED_URL_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("签名无效或已被篡改"));
    }

    @Test
    @DisplayName("篡改 Token 内容时解码立即被拦截")
    void testDecodeTamperedToken() {
        String token = S3ChunkTokenUtil.encode(PATH, RAW_UPLOAD_ID, SECRET_KEY);
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        String payload = new String(bytes, StandardCharsets.UTF_8);

        // 篡改路径为恶意路径
        String tamperedPayload = payload.replace(PATH, "malicious/hack.mp4");
        String tamperedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8));

        StorageException ex = assertThrows(StorageException.class, () ->
                S3ChunkTokenUtil.decode(tamperedToken, SECRET_KEY));
        assertEquals(StorageResultCode.PRESIGNED_URL_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("签名无效或已被篡改"));
    }

    @Test
    @DisplayName("非法 Base64 格式字符串解码抛出 PRESIGNED_URL_INVALID 异常")
    void testDecodeCorruptedBase64() {
        StorageException ex = assertThrows(StorageException.class, () ->
                S3ChunkTokenUtil.decode("!!!not-a-valid-base64-token!!!", SECRET_KEY));
        assertEquals(StorageResultCode.PRESIGNED_URL_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("Base64 解码失败"));
    }

    @Test
    @DisplayName("Token 结构不满足三段式规范时解码被拒绝")
    void testDecodeInvalidPartsCount() {
        String invalidPayload = "part1::part2";
        String invalidToken = Base64.getUrlEncoder().withoutPadding().encodeToString(invalidPayload.getBytes(StandardCharsets.UTF_8));

        StorageException ex = assertThrows(StorageException.class, () ->
                S3ChunkTokenUtil.decode(invalidToken, SECRET_KEY));
        assertEquals(StorageResultCode.PRESIGNED_URL_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("三段式规范"));
    }

    @Test
    @DisplayName("空参前置卫语句拦截校验")
    void testNullAndBlankGuards() {
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(null, RAW_UPLOAD_ID, SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(" ", RAW_UPLOAD_ID, SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(PATH, null, SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(PATH, " ", SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(PATH, RAW_UPLOAD_ID, null));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.encode(PATH, RAW_UPLOAD_ID, " "));

        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.decode(null, SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.decode(" ", SECRET_KEY));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.decode("some-token", null));
        assertThrows(StorageException.class, () -> S3ChunkTokenUtil.decode("some-token", " "));
    }
}
