package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 会话领域实体单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 会话领域实体单元测试")
class TcpSessionTest {

    @Test
    @DisplayName("验证会话生命周期、网络状态与属性上下文读写")
    void testSessionAttributesAndLifecycle() {
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-001", channel);

        assertThat(session.getSessionId()).isEqualTo("SES-001");
        assertThat(session.isActive()).isTrue();
        assertThat(session.getConnectedAt()).isPositive();
        assertThat(session.getLastActiveTime()).isEqualTo(session.getConnectedAt());

        // 属性读写测试
        session.setAttribute("user", "admin");
        session.setAttribute("authLevel", 9);
        assertThat(session.hasAttribute("user")).isTrue();
        assertThat(session.getAttribute("user")).isEqualTo("admin");
        assertThat(session.getAttribute("authLevel", Integer.class)).isEqualTo(9);
        assertThat(session.getAttribute("non-existent")).isNull();

        session.removeAttribute("user");
        assertThat(session.hasAttribute("user")).isFalse();

        // 发送与关闭测试
        session.send("Ping");
        assertThat(session.getLastActiveTime()).isGreaterThanOrEqualTo(session.getConnectedAt());

        session.close(CloseReason.NORMAL);
        assertThat(session.getCloseReason()).isEqualTo(CloseReason.NORMAL);
        assertThat(session.isActive()).isFalse();

        // 断开后发送抛出异常
        assertThatThrownBy(() -> session.send("Pong"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TCP 会话已断开");
    }
}
