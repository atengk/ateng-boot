package io.github.atengk.boot.tcp.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 数据包模型单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 数据包模型单元测试")
class TcpPacketTest {

    @Test
    @DisplayName("验证快捷构建与字段赋值")
    void testPacketFactory() {
        TcpPayload payload = TcpPayload.of("telemetry-data");
        TcpPacket packet = TcpPacket.of("0x0100", "REQ-12345", payload);

        assertThat(packet.getCommand()).isEqualTo("0x0100");
        assertThat(packet.getRequestId()).isEqualTo("REQ-12345");
        assertThat(packet.getPayload().asString()).isEqualTo("telemetry-data");
        assertThat(packet.toString()).contains("0x0100").contains("REQ-12345");
    }

    @Test
    @DisplayName("验证标头元数据读写与强类型获取")
    void testHeaders() {
        TcpPacket packet = TcpPacket.of(TcpPayload.of("data"));
        packet.setHeader("deviceId", "DEV-001");
        packet.setHeader("retryCount", 3);

        assertThat(packet.hasHeader("deviceId")).isTrue();
        assertThat(packet.hasHeader("unknown")).isFalse();
        assertThat(packet.getHeader("deviceId", String.class)).isEqualTo("DEV-001");
        assertThat(packet.getHeader("retryCount", Integer.class)).isEqualTo(3);
        assertThat(packet.getHeader("retryCount", String.class)).isNull();
    }
}
