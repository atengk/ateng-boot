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
}
