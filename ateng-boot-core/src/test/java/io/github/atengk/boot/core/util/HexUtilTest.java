package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 十六进制工具 HexUtil 单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("十六进制编解码工具 HexUtil 测试")
class HexUtilTest {

    @Test
    @DisplayName("encodeHex 正常与空边界编解码验证")
    void shouldEncodeHexCorrectly() {
        assertThat(HexUtil.encodeHex(null)).isNull();
        assertThat(HexUtil.encodeHex(new byte[0])).isEqualTo("");

        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(HexUtil.encodeHex(bytes)).isEqualTo("68656c6c6f");
        assertThat(HexUtil.encodeHex(bytes, false)).isEqualTo("68656C6C6F");

        // 前导 0 字节完整保留验证
        byte[] withLeadingZeros = new byte[]{0x00, 0x01, 0x0f};
        assertThat(HexUtil.encodeHex(withLeadingZeros)).isEqualTo("00010f");
    }

    @Test
    @DisplayName("decodeHex 解码与双向无损验证")
    void shouldDecodeHexCorrectly() {
        assertThat(HexUtil.decodeHex(null)).isEmpty();
        assertThat(HexUtil.decodeHex("")).isEmpty();
        assertThat(HexUtil.decodeHex("   ")).isEmpty();

        // 正常小写与大写解码
        byte[] expected = "world".getBytes(StandardCharsets.UTF_8);
        String hexLower = HexUtil.encodeHex(expected, true);
        String hexUpper = HexUtil.encodeHex(expected, false);

        assertThat(HexUtil.decodeHex(hexLower)).containsExactly(expected);
        assertThat(HexUtil.decodeHex(hexUpper)).containsExactly(expected);

        // 前导 0 无损还原
        byte[] zeroBytes = new byte[]{0x00, 0x00, 0x2a};
        byte[] decoded = HexUtil.decodeHex("00002a");
        assertThat(decoded).containsExactly(zeroBytes);
    }

    @Test
    @DisplayName("非法十六进制输入时抛出明确 IllegalArgumentException")
    void shouldThrowExceptionOnInvalidHex() {
        // 奇数长度
        assertThatThrownBy(() -> HexUtil.decodeHex("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效的十六进制字符串");

        // 包含非法非 Hex 字符
        assertThatThrownBy(() -> HexUtil.decodeHex("zzff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效的十六进制字符串");
    }
}
