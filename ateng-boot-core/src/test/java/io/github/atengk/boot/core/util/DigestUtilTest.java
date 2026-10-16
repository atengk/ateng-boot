package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息摘要算法工具类单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("消息摘要算法工具类单元测试")
class DigestUtilTest {

    @Test
    @DisplayName("验证 SHA-256 标准空字符串摘要")
    void testSha256Empty() {
        // SHA-256("") 标准输出十六进制哈希
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertEquals(emptyHash, DigestUtil.sha256Hex(""));
        assertEquals(emptyHash, DigestUtil.sha256Hex((String) null));
        assertEquals(emptyHash, DigestUtil.sha256Hex(new byte[0]));
        assertEquals(emptyHash, DigestUtil.sha256Hex((byte[]) null));
    }

    @Test
    @DisplayName("验证 SHA-256 字符串摘要计算正确性")
    void testSha256String() {
        String input = "hello world";
        // SHA-256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        String expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        assertEquals(expected, DigestUtil.sha256Hex(input));
        assertEquals(expected, DigestUtil.sha256Hex(input.getBytes(StandardCharsets.UTF_8)));

        byte[] rawBytes = DigestUtil.sha256(input);
        assertNotNull(rawBytes);
        assertEquals(32, rawBytes.length);
    }

    @Test
    @DisplayName("验证 MD5 字符串摘要计算正确性")
    void testMd5String() {
        String input = "hello world";
        // MD5("hello world") = 5eb63bbbe01eeed093cb22bb8f5acdc3
        String expected = "5eb63bbbe01eeed093cb22bb8f5acdc3";
        assertEquals(expected, DigestUtil.md5Hex(input));
        assertEquals(expected, DigestUtil.md5Hex(input.getBytes(StandardCharsets.UTF_8)));
    }
}
