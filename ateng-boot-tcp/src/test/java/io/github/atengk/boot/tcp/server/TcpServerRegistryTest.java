package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 服务端注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpServerRegistryTest {

    @Test
    @DisplayName("注册与获取服务端实例成功")
    void testRegisterAndGetServer() {
        TcpServerRegistry registry = new TcpServerRegistry();
        TcpServerProperties props = new TcpServerProperties();
        props.setPort(8088);
        TcpServer server = new TcpServer("jt808", props, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), new GenericApplicationContext());

        registry.register("jt808", server);

        assertThat(registry.getServer("jt808")).isSameAs(server);
        assertThat(registry.getRequiredServer("jt808")).isSameAs(server);
        assertThat(registry.getAllServers()).containsEntry("jt808", server);
        assertThat(registry.getServer("unknown")).isNull();

        assertThatThrownBy(() -> registry.getRequiredServer("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到名称为 [unknown] 的 TCP 服务端实例");
    }

    @Test
    @DisplayName("默认主服务端获取")
    void testGetDefaultServer() {
        TcpServerRegistry registry = new TcpServerRegistry();
        assertThat(registry.getDefaultServer()).isNull();

        TcpServerProperties props = new TcpServerProperties();
        props.setPort(8080);
        TcpServer defaultServer = new TcpServer(TcpServerRegistry.DEFAULT_SERVER_NAME, props, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), new GenericApplicationContext());

        registry.register(TcpServerRegistry.DEFAULT_SERVER_NAME, defaultServer);
        assertThat(registry.getDefaultServer()).isSameAs(defaultServer);
    }

    @Test
    @DisplayName("验证 contains、unregister 自动停止与同名覆盖自动释放旧服务端实例")
    void testContainsUnregisterAndOverrideStopsOldServer() {
        TcpServerRegistry registry = new TcpServerRegistry();
        GenericApplicationContext eventContext = new GenericApplicationContext();
        eventContext.refresh();

        TcpServerProperties props1 = new TcpServerProperties();
        props1.setHost("127.0.0.1");
        props1.setPort(0);
        TcpServer server1 = new TcpServer("edge", props1, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), eventContext);
        server1.start();
        assertThat(server1.isRunning()).isTrue();

        registry.register("edge", server1);
        assertThat(registry.contains("edge")).isTrue();
        assertThat(registry.contains("missing")).isFalse();
        assertThat(registry.contains(null)).isFalse();

        // 同名注册新实例应自动平滑停止旧实例释放端口
        TcpServerProperties props2 = new TcpServerProperties();
        props2.setHost("127.0.0.1");
        props2.setPort(0);
        TcpServer server2 = new TcpServer("edge", props2, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), eventContext);
        server2.start();
        assertThat(server2.isRunning()).isTrue();

        registry.register("edge", server2);
        assertThat(server1.isRunning()).isFalse();
        assertThat(registry.getRequiredServer("edge")).isSameAs(server2);

        // unregister 移除并自动平滑停止实例
        TcpServer removed = registry.unregister("edge");
        assertThat(removed).isSameAs(server2);
        assertThat(server2.isRunning()).isFalse();
        assertThat(registry.contains("edge")).isFalse();
        assertThat(registry.getServer("edge")).isNull();
        assertThat(registry.unregister("edge")).isNull();
        assertThat(registry.unregister(null)).isNull();
        eventContext.close();
    }

    @Test
    @DisplayName("验证同名覆盖注册严格先平滑停止旧实例再纳管新实例")
    void testOverrideStopsOldServerBeforeExposingNewServer() {
        TcpServerRegistry registry = new TcpServerRegistry();
        GenericApplicationContext eventContext = new GenericApplicationContext();
        eventContext.refresh();

        java.util.concurrent.atomic.AtomicReference<TcpServer> observedDuringOldStop = new java.util.concurrent.atomic.AtomicReference<>();
        TcpServerProperties props = new TcpServerProperties();
        props.setHost("127.0.0.1");
        props.setPort(0);

        TcpServer oldServer = new TcpServer("gw", props, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), eventContext) {
            private volatile boolean mockRunning = true;

            @Override
            public void stop() {
                observedDuringOldStop.set(registry.getServer("gw"));
                mockRunning = false;
            }

            @Override
            public boolean isRunning() {
                return mockRunning;
            }
        };
        TcpServer newServer = new TcpServer("gw", props, new TcpSessionManager(), null, null, Collections.emptyList(), Collections.emptyList(), eventContext);

        registry.register("gw", oldServer);
        registry.register("gw", newServer);

        assertThat(observedDuringOldStop.get()).isSameAs(oldServer);
        assertThat(oldServer.isRunning()).isFalse();
        assertThat(registry.getServer("gw")).isSameAs(newServer);
        eventContext.close();
    }
}
