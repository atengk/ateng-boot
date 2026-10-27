package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChecksumUtil} 纯原生数据传输与硬件校验和工具单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("ChecksumUtil 数据校验和计算工具测试")
class ChecksumUtilTest {

    private static final String SAMPLE_TEXT = "123456789";

    @Test
    @DisplayName("验证标准已知测试向量的 CRC32、CRC32C 与 Adler32 校验和")
    void shouldCalculateChecksumsAccurately() throws IOException {
        byte[] bytes = SAMPLE_TEXT.getBytes(StandardCharsets.UTF_8);

        // 标准 CRC32: "123456789" 的 CRC-32 校验和为 0xcbf43926 (3421780262L)
        long crc32 = ChecksumUtil.crc32(bytes);
        assertEquals(3421780262L, crc32);
        assertEquals("cbf43926", ChecksumUtil.crc32Hex(bytes));
        assertTrue(ChecksumUtil.verifyCrc32(3421780262L, bytes));

        // 标准 Adler32: "123456789" 的 Adler-32 校验和为 0x091e01de (152961502L)
        long adler32 = ChecksumUtil.adler32(bytes);
        assertEquals(152961502L, adler32);
        assertEquals("091e01de", ChecksumUtil.adler32Hex(bytes));
        assertTrue(ChecksumUtil.verifyAdler32(152961502L, bytes));

        // 标准 CRC32C (Castagnoli): "123456789" 的 CRC-32C 校验和为 0xe3069283 (3808858755L)
        long crc32c = ChecksumUtil.crc32c(bytes);
        assertEquals(3808858755L, crc32c);
        assertEquals("e3069283", ChecksumUtil.crc32cHex(bytes));
        assertTrue(ChecksumUtil.verifyCrc32c(3808858755L, bytes));
    }

    @Test
    @DisplayName("应该支持通过流式 InputStream 安全计算校验和且确定性处理流")
    void shouldCalculateChecksumFromInputStream() throws IOException {
        byte[] bytes = "LargeStreamDataChunk".getBytes(StandardCharsets.UTF_8);

        try (InputStream in1 = new ByteArrayInputStream(bytes);
             InputStream in2 = new ByteArrayInputStream(bytes);
             InputStream in3 = new ByteArrayInputStream(bytes)) {

            long crc32Stream = ChecksumUtil.crc32(in1);
            long crc32Bytes = ChecksumUtil.crc32(bytes);
            assertEquals(crc32Bytes, crc32Stream);

            long crc32cStream = ChecksumUtil.crc32c(in2);
            long crc32cBytes = ChecksumUtil.crc32c(bytes);
            assertEquals(crc32cBytes, crc32cStream);

            long adler32Stream = ChecksumUtil.adler32(in3);
            long adler32Bytes = ChecksumUtil.adler32(bytes);
            assertEquals(adler32Bytes, adler32Stream);
        }
    }

    @Test
    @DisplayName("空字符串与空字节数组边界测试")
    void shouldHandleEmptyInputsSafely() {
        byte[] empty = new byte[0];

        assertEquals(0L, ChecksumUtil.crc32(empty));
        assertEquals(0L, ChecksumUtil.crc32(""));
        assertEquals("00000000", ChecksumUtil.crc32Hex(empty));

        assertEquals(0L, ChecksumUtil.crc32c(empty));
        assertEquals("00000000", ChecksumUtil.crc32cHex(empty));

        assertEquals(1L, ChecksumUtil.adler32(empty)); // Adler-32 空串初始值为 1
        assertEquals("00000001", ChecksumUtil.adler32Hex(empty));
    }

    @Test
    @DisplayName("前置防卫：null 入参应该抛出 NullPointerException")
    void shouldThrowNpeOnNullInputs() {
        assertThrows(NullPointerException.class, () -> ChecksumUtil.crc32((byte[]) null));
        assertThrows(NullPointerException.class, () -> ChecksumUtil.crc32((String) null));
        assertThrows(NullPointerException.class, () -> ChecksumUtil.crc32((InputStream) null));
    }
}
