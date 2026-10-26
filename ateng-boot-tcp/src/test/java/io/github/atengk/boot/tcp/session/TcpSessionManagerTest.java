package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpDeviceBoundEvent;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 会话管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 会话管理器单元测试")
class TcpSessionManagerTest {

    @Test
    @DisplayName("验证会话注册、查询与注销流程")
    void testRegisterAndUnregister() {
        TcpSessionManager manager = new TcpSessionManager();
        List<Object> events = new ArrayList<>();
        manager.setApplicationEventPublisher(events::add);

        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-001", channel);

        // 注册
        manager.register(session);
        assertThat(manager.getActiveCount()).isEqualTo(1);
        assertThat(manager.getSession("SES-001")).isSameAs(session);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TcpSessionConnectedEvent.class);

        // 注销
        manager.unregister("SES-001", CloseReason.NORMAL);
        assertThat(manager.getActiveCount()).isEqualTo(0);
        assertThat(manager.getSession("SES-001")).isNull();
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(TcpSessionClosedEvent.class);
        assertThat(((TcpSessionClosedEvent) events.get(1)).getReason()).isEqualTo(CloseReason.NORMAL);
    }

    @Test
    @DisplayName("验证设备标识绑定、双向映射与重连“踢旧留新”置换治理")
    void testDeviceBindingAndKickOut() {
        TcpSessionManager manager = new TcpSessionManager();
        List<Object> events = new ArrayList<>();
        manager.setApplicationEventPublisher(events::add);

        EmbeddedChannel channel1 = new EmbeddedChannel();
        TcpSession session1 = new TcpSession("SES-1", channel1);
        manager.register(session1);

        // 绑定设备 DEV-001 到 session1
        manager.bind("SES-1", "DEV-001");
        assertThat(session1.getDeviceId()).isEqualTo("DEV-001");
        assertThat(manager.getSessionByDeviceId("DEV-001")).isSameAs(session1);
        assertThat(events).hasSize(2); // Connected + Bound
        assertThat(events.get(1)).isInstanceOf(TcpDeviceBoundEvent.class);

        // 模拟网络抖动后，同一设备带着全新连接 channel2 (SES-2) 重新上线
        EmbeddedChannel channel2 = new EmbeddedChannel();
        TcpSession session2 = new TcpSession("SES-2", channel2);
        manager.register(session2);

        // 对 session2 执行绑定 DEV-001 -> 触发“踢旧留新”
        manager.bind("SES-2", "DEV-001");

        // 验证旧 session1 已经被物理关闭，原因为 KICK_OUT
        assertThat(session1.isActive()).isFalse();
        assertThat(session1.getCloseReason()).isEqualTo(CloseReason.KICK_OUT);
        assertThat(manager.getSession("SES-1")).isNull();

        // 验证新 session2 成为唯一绑定的活跃会话
        assertThat(session2.getDeviceId()).isEqualTo("DEV-001");
        assertThat(manager.getSessionByDeviceId("DEV-001")).isSameAs(session2);
        assertThat(manager.getActiveCount()).isEqualTo(1);

        // 解绑测试
        manager.unbind("DEV-001");
        assertThat(manager.getSessionByDeviceId("DEV-001")).isNull();
        assertThat(session2.getDeviceId()).isNull();
    }

    @Test
    @DisplayName("验证同一会话重绑定新设备时逆向解绑旧设备映射")
    void testDeviceRebindingCleansUpOldDevice() {
        TcpSessionManager manager = new TcpSessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-REBIND", ch);
        manager.register(session);

        manager.bind("SES-REBIND", "DEV-OLD");
        assertThat(manager.getSessionByDeviceId("DEV-OLD")).isSameAs(session);

        // 重绑定到新设备
        manager.bind("SES-REBIND", "DEV-NEW");
        assertThat(manager.getSessionByDeviceId("DEV-NEW")).isSameAs(session);
        assertThat(manager.getSessionByDeviceId("DEV-OLD")).isNull();
        assertThat(session.getDeviceId()).isEqualTo("DEV-NEW");
    }

    @Test
    @DisplayName("验证重连时半死僵尸通道被彻底注销移除")
    void testKickOutZombieSession() {
        TcpSessionManager manager = new TcpSessionManager();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        TcpSession session1 = new TcpSession("SES-ZOMBIE", ch1);
        manager.register(session1);
        manager.bind("SES-ZOMBIE", "DEV-ZOMBIE");

        // 模拟底层网络闪断使通道变为半死非活跃状态
        ch1.close();
        assertThat(session1.isActive()).isFalse();

        // 设备使用全新通道上线
        EmbeddedChannel ch2 = new EmbeddedChannel();
        TcpSession session2 = new TcpSession("SES-ALIVE", ch2);
        manager.register(session2);
        manager.bind("SES-ALIVE", "DEV-ZOMBIE");

        // 验证旧僵尸通道被彻底从 sessions 中移除
        assertThat(manager.getSession("SES-ZOMBIE")).isNull();
        assertThat(manager.getSessionByDeviceId("DEV-ZOMBIE")).isSameAs(session2);
        assertThat(manager.getActiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("验证通过设备标识主动关闭会话")
    void testCloseDevice() {
        TcpSessionManager manager = new TcpSessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession session = new TcpSession("SES-DEV-CLOSE", ch);
        manager.register(session);
        manager.bind("SES-DEV-CLOSE", "DEV-TO-CLOSE");

        manager.closeDevice("DEV-TO-CLOSE", CloseReason.NORMAL);

        assertThat(session.isActive()).isFalse();
        assertThat(manager.getSession("SES-DEV-CLOSE")).isNull();
        assertThat(manager.getSessionByDeviceId("DEV-TO-CLOSE")).isNull();
        assertThat(manager.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("验证逻辑分组组播管理")
    void testGroupManagement() {
        TcpSessionManager manager = new TcpSessionManager();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();
        TcpSession s1 = new TcpSession("S1", ch1);
        TcpSession s2 = new TcpSession("S2", ch2);
        manager.register(s1);
        manager.register(s2);

        manager.addToGroup("GATEWAY", "S1");
        manager.addToGroup("GATEWAY", "S2");

        assertThat(manager.getGroupSessions("GATEWAY")).containsExactlyInAnyOrder(s1, s2);
        assertThat(manager.getGroupSessions("NON_EXISTENT")).isEmpty();

        manager.removeFromGroup("GATEWAY", "S1");
        assertThat(manager.getGroupSessions("GATEWAY")).containsExactly(s2);

        // 会话断开注销时自动清理分组
        manager.unregister("S2", CloseReason.NORMAL);
        assertThat(manager.getGroupSessions("GATEWAY")).isEmpty();
    }
}
