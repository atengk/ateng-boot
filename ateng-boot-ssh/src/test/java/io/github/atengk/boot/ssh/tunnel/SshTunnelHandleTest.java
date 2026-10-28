package io.github.atengk.boot.ssh.tunnel;

import io.github.atengk.boot.ssh.model.SshServerInfo;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * SshTunnelHandle 受管句柄状态与生命周期单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshTunnelHandle 受管句柄单元测试")
class SshTunnelHandleTest {

    @Test
    @DisplayName("测试参数校验防卫：tracker为null、非法端口或空主机快速失败")
    void testConstructorValidation() {
        ExplicitPortForwardingTracker tracker = mock(ExplicitPortForwardingTracker.class);
        SshServerInfo server = SshServerInfo.of("192.168.1.10", 22, "root", "123456");

        assertThatThrownBy(() -> new SshTunnelHandle(server, null, "127.0.0.1", 8080, "db.internal", 3306, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ExplicitPortForwardingTracker must not be null");

        assertThatThrownBy(() -> new SshTunnelHandle(server, tracker, "127.0.0.1", 8080, "", 3306, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remoteHost must not be blank");

        assertThatThrownBy(() -> new SshTunnelHandle(server, tracker, "127.0.0.1", 0, "db.internal", 3306, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localPort must be between 1 and 65535");

        assertThatThrownBy(() -> new SshTunnelHandle(server, tracker, "127.0.0.1", 8080, "db.internal", 70000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePort must be between 1 and 65535");
    }

    @Test
    @DisplayName("测试状态判定与幂等关闭：确定性释放物理资源与触发回调")
    void testLifecycleAndCloseIdempotence() throws IOException {
        ExplicitPortForwardingTracker tracker = mock(ExplicitPortForwardingTracker.class);
        ClientSession session = mock(ClientSession.class);
        when(tracker.isOpen()).thenReturn(true);
        when(tracker.getSession()).thenReturn(session);
        when(session.isOpen()).thenReturn(true);

        SshServerInfo server = SshServerInfo.of("bastion.internal", 22, "ops", "pass");
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        SshTunnelHandle handle = new SshTunnelHandle(
                server,
                tracker,
                "127.0.0.1",
                54321,
                "mysql.prod",
                3306,
                tunnel -> callbackFired.set(true)
        );

        assertThat(handle.getLocalHost()).isEqualTo("127.0.0.1");
        assertThat(handle.getLocalPort()).isEqualTo(54321);
        assertThat(handle.getRemoteHost()).isEqualTo("mysql.prod");
        assertThat(handle.getRemotePort()).isEqualTo(3306);
        assertThat(handle.getServerInfo()).isSameAs(server);
        assertThat(handle.getCreationTime()).isPositive();
        assertThat(handle.isAlive()).isTrue();
        assertThat(handle.isClosed()).isFalse();
        assertThat(handle.toString()).contains("127.0.0.1:54321").contains("mysql.prod:3306");

        // 第一次关闭
        handle.close();
        assertThat(handle.isClosed()).isTrue();
        assertThat(handle.isAlive()).isFalse();
        assertThat(callbackFired.get()).isTrue();
        verify(tracker, times(1)).close();

        // 重复关闭（幂等测试，不应重复触发 tracker.close）
        handle.close();
        verify(tracker, times(1)).close();
    }
}
