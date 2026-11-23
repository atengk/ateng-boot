package io.github.atengk.boot.sample.tcp.codec;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JT/T 808 复合报文编解码器单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
class Jt808CompositeFrameCodecTest {

    @Test
    @DisplayName("测试国标复合报文编码与解码正向链路")
    void testEncodeAndDecode() {
        LocationReportPacket packet = new LocationReportPacket(
                "888888",
                1024L,
                116.407412,
                39.904231,
                88.5,
                "ON",
                System.currentTimeMillis()
        );

        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport(packet);
        assertThat(frame).isNotNull();
        assertThat(frame[0]).isEqualTo(Jt808CompositeFrameCodec.DELIMITER);
        assertThat(frame[frame.length - 1]).isEqualTo(Jt808CompositeFrameCodec.DELIMITER);

        LocationReportPacket decoded = Jt808CompositeFrameCodec.decodeLocationReport(frame);
        assertThat(decoded).isNotNull();
        assertThat(decoded.getDeviceId()).isEqualTo("888888");
        assertThat(decoded.getSeq()).isEqualTo(1024L);
        assertThat(decoded.getLongitude()).isEqualTo(Math.round(116.407412 * 1_000_000) / 1_000_000.0);
        assertThat(decoded.getLatitude()).isEqualTo(Math.round(39.904231 * 1_000_000) / 1_000_000.0);
        assertThat(decoded.getSpeed()).isEqualTo(88.5);
        assertThat(decoded.getEngineStatus()).isEqualTo("ON");
    }

    @Test
    @DisplayName("测试截断报文长度不足 34 字节时快速失败，防御 BufferUnderflowException")
    void testTruncatedBodyThrowsIllegalArgumentException() {
        // 构造仅 25 字节的数据（在 23-33 字节危险区间）
        byte[] truncated = new byte[25];
        truncated[0] = Jt808CompositeFrameCodec.DELIMITER;
        truncated[truncated.length - 1] = Jt808CompositeFrameCodec.DELIMITER;

        assertThatThrownBy(() -> Jt808CompositeFrameCodec.decodeLocationReport(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效长度不足");
    }

    @Test
    @DisplayName("测试 BCC 异或校验码篡改时抛出异常")
    void testBccTamperingThrowsException() {
        byte[] frame = Jt808CompositeFrameCodec.encodeLocationReport("123456", 1L, 120.1, 30.2, 60.0, "OFF");
        // 篡改倒数第二个字节（BCC 校验码所在位置，倒数第一是 0x7E）
        frame[frame.length - 2] = (byte) (frame[frame.length - 2] ^ 0xFF);

        assertThatThrownBy(() -> Jt808CompositeFrameCodec.decodeLocationReport(frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BCC 校验和不匹配");
    }

    @Test
    @DisplayName("测试首尾缺少 0x7E 标识符时拦截")
    void testMissingDelimiterThrowsException() {
        byte[] invalidFrame = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        assertThatThrownBy(() -> Jt808CompositeFrameCodec.decodeLocationReport(invalidFrame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 0x7E 标识符");
    }
}
