package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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

    @Test
    @DisplayName("验证 SHA-1 标准向量与空边界摘要计算正确性 (FIPS 180-4)")
    void testSha1String() {
        String expected = "a9993e364706816aba3e25717850c26c9cd0d89d";
        assertEquals(expected, DigestUtil.sha1Hex("abc"));
        assertEquals(expected, DigestUtil.sha1Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(20, DigestUtil.sha1("abc").length);

        String emptySha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        assertEquals(emptySha1, DigestUtil.sha1Hex(""));
        assertEquals(emptySha1, DigestUtil.sha1Hex((String) null));
        assertEquals(emptySha1, DigestUtil.sha1Hex(new byte[0]));
        assertEquals(emptySha1, DigestUtil.sha1Hex((byte[]) null));
    }

    @Test
    @DisplayName("验证 SHA-512 标准向量与空边界摘要计算正确性 (FIPS 180-4)")
    void testSha512StandardVectors() {
        String emptySha512 = "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e";
        assertEquals(emptySha512, DigestUtil.sha512Hex(""));
        assertEquals(emptySha512, DigestUtil.sha512Hex((String) null));
        assertEquals(emptySha512, DigestUtil.sha512Hex(new byte[0]));
        assertEquals(emptySha512, DigestUtil.sha512Hex((byte[]) null));

        String abcSha512 = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
        assertEquals(abcSha512, DigestUtil.sha512Hex("abc"));
        assertEquals(64, DigestUtil.sha512("abc").length);
    }

    @Test
    @DisplayName("流式 InputStream 摘要：跨缓冲区大字节流一致性、不关闭外部流与异常防御")
    void testStreamingDigestsConsistentAndDoNotCloseStream() {
        byte[] largePayload = new byte[25_000];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) ((i * 73 + 19) & 0xff);
        }

        class TrackingInputStream extends FilterInputStream {
            boolean closed = false;

            TrackingInputStream(InputStream in) {
                super(in);
            }

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        TrackingInputStream sha256Stream = new TrackingInputStream(new ByteArrayInputStream(largePayload));
        assertEquals(DigestUtil.sha256Hex(largePayload), DigestUtil.sha256Hex(sha256Stream));
        assertFalse(sha256Stream.closed);

        TrackingInputStream sha512Stream = new TrackingInputStream(new ByteArrayInputStream(largePayload));
        assertEquals(DigestUtil.sha512Hex(largePayload), DigestUtil.sha512Hex(sha512Stream));
        assertFalse(sha512Stream.closed);

        TrackingInputStream sm3Stream = new TrackingInputStream(new ByteArrayInputStream(largePayload));
        assertEquals(DigestUtil.sm3Hex(largePayload), DigestUtil.sm3Hex(sm3Stream));
        assertFalse(sm3Stream.closed);

        // 空流边界
        assertEquals(DigestUtil.sha256Hex(new byte[0]), DigestUtil.sha256Hex(new ByteArrayInputStream(new byte[0])));
        assertEquals(DigestUtil.sha512Hex(new byte[0]), DigestUtil.sha512Hex(new ByteArrayInputStream(new byte[0])));
        assertEquals(DigestUtil.sm3Hex(new byte[0]), DigestUtil.sm3Hex(new ByteArrayInputStream(new byte[0])));

        // null 流抛 IllegalArgumentException；IOException 包装为 UncheckedIOException
        assertThrows(IllegalArgumentException.class, () -> DigestUtil.sha256((InputStream) null));
        assertThrows(IllegalArgumentException.class, () -> DigestUtil.sha512((InputStream) null));
        assertThrows(IllegalArgumentException.class, () -> DigestUtil.sm3((InputStream) null));

        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk read error");
            }
        };
        assertThrows(UncheckedIOException.class, () -> DigestUtil.sha256(failingStream));
        assertThrows(UncheckedIOException.class, () -> DigestUtil.sm3(failingStream));
    }

    @Test
    @DisplayName("SM3 多段增量摘要与整块拼接摘要严格一致")
    void testSm3MultipartConsistency() {
        byte[] p1 = "head-segment-".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "a".repeat(130).getBytes(StandardCharsets.UTF_8);
        byte[] p3 = "-tail-segment".getBytes(StandardCharsets.UTF_8);

        byte[] combined = new byte[p1.length + p2.length + p3.length];
        System.arraycopy(p1, 0, combined, 0, p1.length);
        System.arraycopy(p2, 0, combined, p1.length, p2.length);
        System.arraycopy(p3, 0, combined, p1.length + p2.length, p3.length);

        assertArrayEquals(DigestUtil.sm3(combined), DigestUtil.sm3(p1, null, p2, new byte[0], p3));
    }
}
