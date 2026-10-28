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
    @DisplayName("验证国密 SM3 标准测试向量计算正确性 (GB/T 32905-2016)")
    void testSm3StandardVector() {
        // 1. 标准向量 1: "abc"
        String msg1 = "abc";
        String expectedHash1 = "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0";
        assertEquals(expectedHash1, DigestUtil.sm3Hex(msg1));
        assertEquals(expectedHash1, DigestUtil.sm3Hex(msg1.getBytes(StandardCharsets.UTF_8)));

        byte[] rawBytes1 = DigestUtil.sm3(msg1);
        assertNotNull(rawBytes1);
        assertEquals(32, rawBytes1.length);

        // 2. 标准向量 2: "abcd" 重复 16 次 (64 字节)
        String msg2 = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        String expectedHash2 = "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732";
        assertEquals(expectedHash2, DigestUtil.sm3Hex(msg2));

        // 3. 空与 null 校验
        assertNotNull(DigestUtil.sm3(""));
        assertEquals(32, DigestUtil.sm3((String) null).length);
        assertEquals(DigestUtil.sm3Hex(""), DigestUtil.sm3Hex((String) null));
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
