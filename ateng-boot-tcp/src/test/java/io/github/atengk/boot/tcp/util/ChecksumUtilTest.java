package io.github.atengk.boot.tcp.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验和与工业清洗工具单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("校验和与工业清洗工具单元测试")
class ChecksumUtilTest {

    @Test
    @DisplayName("验证 XOR 异或校验计算与比对")
    void testXor() {
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
        // 1 ^ 2 ^ 3 ^ 4 = (1^2=3) ^ 3 = 0 ^ 4 = 4
        byte xor = ChecksumUtil.calculateXor(data);
        assertThat(xor).isEqualTo((byte) 4);
        assertThat(ChecksumUtil.verifyXor(data, 0, data.length, (byte) 4)).isTrue();
        assertThat(ChecksumUtil.verifyXor(data, 0, data.length, (byte) 5)).isFalse();

        // 边界测试
        assertThat(ChecksumUtil.calculateXor(null)).isEqualTo((byte) 0);
        assertThat(ChecksumUtil.calculateXor(new byte[0])).isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("验证 CRC16-Modbus 校验计算与比对")
    void testCrc16Modbus() {
        // 标准 Modbus 测试用例: 01 03 00 00 00 02 -> CRC16-Modbus = 0x0BC4
        byte[] data = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x02};
        int crc = ChecksumUtil.calculateCrc16Modbus(data);
        assertThat(crc).isEqualTo(0x0BC4);
        assertThat(ChecksumUtil.verifyCrc16Modbus(data, 0, data.length, 0x0BC4)).isTrue();
        assertThat(ChecksumUtil.verifyCrc16Modbus(data, 0, data.length, 0x1234)).isFalse();

        // 边界测试
        assertThat(ChecksumUtil.calculateCrc16Modbus(null)).isEqualTo(0);
        assertThat(ChecksumUtil.calculateCrc16Modbus(new byte[0])).isEqualTo(0);
    }

    @Test
    @DisplayName("验证 CRC16-CCITT 校验计算与比对")
    void testCrc16Ccitt() {
        byte[] data = "123456789".getBytes();
        int crc = ChecksumUtil.calculateCrc16Ccitt(data);
        assertThat(crc).isEqualTo(0x29B1);
        assertThat(ChecksumUtil.verifyCrc16Ccitt(data, 0, data.length, 0x29B1)).isTrue();

        // 边界测试
        assertThat(ChecksumUtil.calculateCrc16Ccitt(null)).isEqualTo(0);
        assertThat(ChecksumUtil.calculateCrc16Ccitt(new byte[0])).isEqualTo(0);
    }

    @Test
    @DisplayName("验证 CRC32 校验计算与比对")
    void testCrc32() {
        byte[] data = "123456789".getBytes();
        // 标准 CRC32: 0xCBF43926 = 3421780262L
        long crc = ChecksumUtil.calculateCrc32(data);
        assertThat(crc).isEqualTo(3421780262L);
        assertThat(ChecksumUtil.verifyCrc32(data, 0, data.length, 3421780262L)).isTrue();

        // 边界测试
        assertThat(ChecksumUtil.calculateCrc32(null)).isEqualTo(0L);
        assertThat(ChecksumUtil.calculateCrc32(new byte[0])).isEqualTo(0L);
    }

    @Test
    @DisplayName("验证交通部 JT/T 808 国标转义与还原清洗")
    void testJt808EscapeAndUnescape() {
        // 原始数据包含 0x7E 与 0x7D
        byte[] raw = new byte[]{0x01, 0x7E, 0x02, 0x7D, 0x03};

        // 转义后: 0x7E -> 0x7D 0x02; 0x7D -> 0x7D 0x01
        byte[] escaped = ChecksumUtil.escapeJt808(raw);
        assertThat(escaped).containsExactly(0x01, 0x7D, 0x02, 0x02, 0x7D, 0x01, 0x03);

        // 还原反转义
        byte[] unescaped = ChecksumUtil.unescapeJt808(escaped);
        assertThat(unescaped).containsExactly(raw);

        // 边界测试
        assertThat(ChecksumUtil.escapeJt808(null)).isEmpty();
        assertThat(ChecksumUtil.unescapeJt808(null)).isEmpty();
    }

    @Test
    @DisplayName("验证十六进制 HEX 编码与解码")
    void testHexConversions() {
        byte[] bytes = new byte[]{(byte) 0x7E, 0x01, (byte) 0xA5, (byte) 0xFF};
        String hex = ChecksumUtil.toHexString(bytes);
        assertThat(hex).isEqualTo("7E01A5FF");

        byte[] decoded = ChecksumUtil.fromHexString(hex);
        assertThat(decoded).containsExactly(bytes);

        // 带 0x 前缀
        byte[] fromPrefix = ChecksumUtil.fromHexString("0x7E01");
        assertThat(fromPrefix).containsExactly((byte) 0x7E, 0x01);

        // 空值测试
        assertThat(ChecksumUtil.toHexString(null)).isEmpty();
        assertThat(ChecksumUtil.fromHexString("")).isEmpty();

        // 异常字符测试
        assertThatThrownBy(() -> ChecksumUtil.fromHexString("7G01"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
