package io.github.atengk.boot.tcp.template;

import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 服务端核心操作门面单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 服务端核心操作门面单元测试")
class TcpServerTemplateTest {

    @Test
    @DisplayName("验证根据 sessionId 单播发送数据")
    void testSendBySessionId() {
        TcpSessionManager manager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(manager);

        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-001", channel);
        manager.register(session);

        // 发送字符串消息
        template.send("SES-001", "Hello TCP");
        Object out = channel.readOutbound();
        assertThat(out).isNotNull();
        assertThat(out).isEqualTo("Hello TCP");

        // 发送空消息 -> wrapPayload 转换为 TcpPayload.empty()
        template.send("SES-001", null);
        Object emptyOut = channel.readOutbound();
        assertThat(emptyOut).isInstanceOf(TcpPayload.class);
        assertThat(((TcpPayload) emptyOut).length()).isEqualTo(0);

        // 不存在的 sessionId 抛出异常
        assertThatThrownBy(() -> template.send("NON-EXISTENT", "msg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未找到对应的活跃 TCP 会话");

        // null sessionId 抛出异常
        assertThatThrownBy(() -> template.send(null, "msg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("验证根据 deviceId 单播发送数据")
    void testSendByDeviceId() {
        TcpSessionManager manager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(manager);

        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-001", channel);
        manager.register(session);
        manager.bind("SES-001", "DEV-888");

        template.sendToDevice("DEV-888", "Ping Device");
        Object out = channel.readOutbound();
        assertThat(out).isEqualTo("Ping Device");

        // 未绑定的 deviceId 抛出异常
        assertThatThrownBy(() -> template.sendToDevice("DEV-999", "msg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("设备未在线或连接已断开");

        // null deviceId 抛出异常
        assertThatThrownBy(() -> template.sendToDevice(null, "msg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("验证全局广播发送")
    void testBroadcast() {
        TcpSessionManager manager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(manager);

        EmbeddedChannel channel1 = new EmbeddedChannel();
        TcpSession session1 = new TcpSession("SES-1", channel1);
        manager.register(session1);

        EmbeddedChannel channel2 = new EmbeddedChannel();
        TcpSession session2 = new TcpSession("SES-2", channel2);
        manager.register(session2);

        template.broadcast("Broadcast Message");

        assertThat((Object) channel1.readOutbound()).isEqualTo("Broadcast Message");
        assertThat((Object) channel2.readOutbound()).isEqualTo("Broadcast Message");
    }

    @Test
    @DisplayName("验证分组组播发送")
    void testSendToGroup() {
        TcpSessionManager manager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(manager);

        EmbeddedChannel ch1 = new EmbeddedChannel();
        TcpSession s1 = new TcpSession("S1", ch1);
        manager.register(s1);
        manager.addToGroup("GATEWAY", "S1");

        EmbeddedChannel ch2 = new EmbeddedChannel();
        TcpSession s2 = new TcpSession("S2", ch2);
        manager.register(s2);
        manager.addToGroup("GATEWAY", "S2");

        EmbeddedChannel ch3 = new EmbeddedChannel();
        TcpSession s3 = new TcpSession("S3", ch3);
        manager.register(s3);
        manager.addToGroup("SENSOR", "S3");

        // 向 GATEWAY 分组组播
        template.sendToGroup("GATEWAY", "Gateway Alert");

        assertThat((Object) ch1.readOutbound()).isEqualTo("Gateway Alert");
        assertThat((Object) ch2.readOutbound()).isEqualTo("Gateway Alert");
        assertThat((Object) ch3.readOutbound()).isNull();

        // 向 null 分组发送应静默处理
        template.sendToGroup(null, "noop");
    }

    @Test
    @DisplayName("验证操作门面活跃统计与属性访问")
    void testFacadeAccessors() {
        TcpSessionManager manager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(manager);

        assertThat(template.getActiveConnections()).isEqualTo(0);
        assertThat(template.getSessionManager()).isSameAs(manager);

        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-1", channel);
        manager.register(session);

        assertThat(template.getActiveConnections()).isEqualTo(1);
    }
}
