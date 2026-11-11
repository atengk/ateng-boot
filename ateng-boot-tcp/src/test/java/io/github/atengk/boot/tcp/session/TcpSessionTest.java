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
    @DisplayName("验证背压策略 BLOCK_TIMEOUT: Netty EventLoop 线程内严禁同步阻塞，自动触发防死锁拦截")
    void testBackpressureBlockTimeoutRejectsInEventLoop() {
        Channel mockChannel = mock(Channel.class);
        io.netty.channel.EventLoop mockEventLoop = mock(io.netty.channel.EventLoop.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.isWritable()).thenReturn(false);
        when(mockChannel.eventLoop()).thenReturn(mockEventLoop);
        when(mockEventLoop.inEventLoop()).thenReturn(true);

        TcpSession session = new TcpSession("SES-DEADLOCK-GUARD", mockChannel);
        session.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT);

        assertThatThrownBy(() -> session.send("Flood"))
                .isInstanceOf(TcpBackpressureException.class)
                .hasMessageContaining("EventLoop 线程内严禁同步阻塞等待背压以防死锁")
                .hasMessageContaining("SES-DEADLOCK-GUARD");
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

    @Test
    @DisplayName("验证背压策略两参重载: 同时设置流控策略与阻塞等待超时，并拦截负数超时")
    void testBackpressurePolicyWithTimeoutOverload() {
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.isWritable()).thenReturn(false);

        TcpSession session = new TcpSession("SES-OVERLOAD", mockChannel);
        session.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT, java.time.Duration.ofMillis(250));

        assertThat(session.getBackpressurePolicy()).isEqualTo(BackpressurePolicy.BLOCK_TIMEOUT);
        assertThat(session.getBackpressureTimeout()).isEqualTo(java.time.Duration.ofMillis(250));

        // 传入 null 参数时保留已配置值
        session.setBackpressurePolicy(BackpressurePolicy.FAIL_FAST, null);
        assertThat(session.getBackpressurePolicy()).isEqualTo(BackpressurePolicy.FAIL_FAST);
        assertThat(session.getBackpressureTimeout()).isEqualTo(java.time.Duration.ofMillis(250));

        session.setBackpressurePolicy(null, java.time.Duration.ofMillis(120));
        assertThat(session.getBackpressurePolicy()).isEqualTo(BackpressurePolicy.FAIL_FAST);
        assertThat(session.getBackpressureTimeout()).isEqualTo(java.time.Duration.ofMillis(120));

        // 负数超时快速失败
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT, java.time.Duration.ofMillis(-5))
        );
    }
}
