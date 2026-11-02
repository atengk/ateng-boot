package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.exception.TcpBackpressureException;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("验证通道可写与积压自省方法")
    void testWritabilityAndPendingBytes() {
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-002", channel);

        assertThat(session.isWritable()).isTrue();
        assertThat(session.getPendingWriteBytes()).isGreaterThanOrEqualTo(0L);

        // channel 为 null 时的保底防御
        TcpSession nullChannelSession = new TcpSession("SES-NULL", null);
        assertThat(nullChannelSession.isWritable()).isFalse();
        assertThat(nullChannelSession.getPendingWriteBytes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("验证背压策略 FAIL_FAST: 通道不可写时立即抛出 TcpBackpressureException")
    void testBackpressureFailFast() {
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.isWritable()).thenReturn(false);

        TcpSession session = new TcpSession("SES-SLOW", mockChannel);
        session.setBackpressurePolicy(BackpressurePolicy.FAIL_FAST);

        assertThat(session.isWritable()).isFalse();
        assertThatThrownBy(() -> session.send("Flood"))
                .isInstanceOf(TcpBackpressureException.class)
                .hasMessageContaining("触发背压快速失败")
                .hasMessageContaining("SES-SLOW");
    }

    @Test
    @DisplayName("验证背压策略 BLOCK_TIMEOUT: 通道不可写时超时抛出 TcpBackpressureException")
    void testBackpressureBlockTimeout() {
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.isWritable()).thenReturn(false);

        TcpSession session = new TcpSession("SES-TIMEOUT", mockChannel);
        session.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT);
        session.setBackpressureTimeout(Duration.ofMillis(50));

        assertThatThrownBy(() -> session.send("Flood"))
                .isInstanceOf(TcpBackpressureException.class)
                .hasMessageContaining("等待可写超时")
                .hasMessageContaining("SES-TIMEOUT");
    }

    @Test
    @DisplayName("验证背压策略 WARN_AND_SEND: 通道不可写时记录告警并放行写入")
    void testBackpressureWarnAndSend() {
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.isWritable()).thenReturn(false);
        DefaultChannelPromise promise = new DefaultChannelPromise(mockChannel);
        when(mockChannel.writeAndFlush(any())).thenReturn(promise);

        TcpSession session = new TcpSession("SES-WARN", mockChannel);
        session.setBackpressurePolicy(BackpressurePolicy.WARN_AND_SEND);

        // 不抛出异常，正常推流
        session.send("Allowed");
        assertThat(session.isWritable()).isFalse();
    }
}
