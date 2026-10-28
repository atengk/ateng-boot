package io.github.atengk.boot.realtime.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时关闭状态码契约常量单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RealtimeCloseStatusTest {

    @Test
    void testCloseStatusCodes() {
        assertThat(RealtimeCloseStatus.NORMAL.getCode()).isEqualTo(1000);
        assertThat(RealtimeCloseStatus.SERVICE_RESTART.getCode()).isEqualTo(1012);
        assertThat(RealtimeCloseStatus.AUTH_FAILED.getCode()).isEqualTo(4001);
        assertThat(RealtimeCloseStatus.USER_KICKED_OUT.getCode()).isEqualTo(4002);
        assertThat(RealtimeCloseStatus.MAX_CONNECTIONS_EXCEEDED.getCode()).isEqualTo(4003);
        assertThat(RealtimeCloseStatus.IDLE_TIMEOUT.getCode()).isEqualTo(4004);
    }
}
