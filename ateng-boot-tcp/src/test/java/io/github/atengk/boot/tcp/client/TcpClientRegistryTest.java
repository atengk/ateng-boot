package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 客户端注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpClientRegistryTest {

    @Test
    @DisplayName("注册与获取客户端实例及操作门面成功")
    void testRegisterAndGetClient() {
        TcpClientRegistry registry = new TcpClientRegistry();
        TcpClientProperties props = new TcpClientProperties();
        props.setPort(9090);

        TcpRequestCorrelationManager correlationManager = new TcpRequestCorrelationManager();
        TcpClient client = new TcpClient("clientA", props, Collections.emptyList(), Collections.emptyList(), new GenericApplicationContext(), correlationManager, null);
        TcpClientTemplate template = new TcpClientTemplate(client, props, correlationManager);

        registry.register("clientA", client, template);

        assertThat(registry.getClient("clientA")).isSameAs(client);
        assertThat(registry.getRequiredClient("clientA")).isSameAs(client);
        assertThat(registry.getTemplate("clientA")).isSameAs(template);
        assertThat(registry.getRequiredTemplate("clientA")).isSameAs(template);
        assertThat(registry.getAllClients()).containsEntry("clientA", client);
        assertThat(registry.getAllTemplates()).containsEntry("clientA", template);

        assertThatThrownBy(() -> registry.getRequiredClient("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到名称为 [unknown] 的 TCP 客户端实例");

        assertThatThrownBy(() -> registry.getRequiredTemplate("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到名称为 [unknown] 的 TCP 客户端操作门面");
    }

    @Test
    @DisplayName("获取默认主客户端实例及门面")
    void testGetDefaultClient() {
        TcpClientRegistry registry = new TcpClientRegistry();
        assertThat(registry.getDefaultClient()).isNull();
        assertThat(registry.getDefaultTemplate()).isNull();

        TcpClientProperties props = new TcpClientProperties();
        TcpRequestCorrelationManager correlationManager = new TcpRequestCorrelationManager();
        TcpClient defaultClient = new TcpClient(TcpClientRegistry.DEFAULT_CLIENT_NAME, props, Collections.emptyList(), Collections.emptyList(), new GenericApplicationContext(), correlationManager, null);
        TcpClientTemplate defaultTemplate = new TcpClientTemplate(defaultClient, props, correlationManager);

        registry.register(TcpClientRegistry.DEFAULT_CLIENT_NAME, defaultClient, defaultTemplate);
        assertThat(registry.getDefaultClient()).isSameAs(defaultClient);
        assertThat(registry.getDefaultTemplate()).isSameAs(defaultTemplate);
    }

    @Test
    @DisplayName("验证 contains、unregister 自动停止与同名覆盖自动释放旧客户端实例")
    void testContainsUnregisterAndOverrideStopsOldClient() {
        TcpClientRegistry registry = new TcpClientRegistry();
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();

        TcpClientProperties props1 = new TcpClientProperties();
        props1.setHost("127.0.0.1");
        props1.setPort(19991);
        props1.getReconnect().setEnabled(true);
        TcpRequestCorrelationManager cm1 = new TcpRequestCorrelationManager();
        TcpClient client1 = new TcpClient("partnerA", props1, Collections.emptyList(), Collections.emptyList(), ctx, cm1, null) {
            private volatile boolean mockRunning = true;

            @Override
            public void stop() {
                mockRunning = false;
            }

            @Override
            public boolean isRunning() {
                return mockRunning;
            }
        };
        TcpClientTemplate template1 = new TcpClientTemplate(client1, props1, cm1);

        registry.register("partnerA", client1, template1);
        assertThat(registry.contains("partnerA")).isTrue();
        assertThat(registry.contains("missing")).isFalse();
        assertThat(registry.contains(null)).isFalse();

        TcpClient client2 = new TcpClient("partnerA", props1, Collections.emptyList(), Collections.emptyList(), ctx, cm1, null) {
            private volatile boolean mockRunning = true;

            @Override
            public void stop() {
                mockRunning = false;
            }

            @Override
            public boolean isRunning() {
                return mockRunning;
            }
        };
        TcpClientTemplate template2 = new TcpClientTemplate(client2, props1, cm1);

        // 同名不同实例注册应自动停止旧实例
        registry.register("partnerA", client2, template2);
        assertThat(client1.isRunning()).isFalse();
        assertThat(registry.getRequiredClient("partnerA")).isSameAs(client2);
        assertThat(registry.getRequiredTemplate("partnerA")).isSameAs(template2);

        // unregister 同时清理 client 与 template 并停止实例
        TcpClient removed = registry.unregister("partnerA");
        assertThat(removed).isSameAs(client2);
        assertThat(client2.isRunning()).isFalse();
        assertThat(registry.contains("partnerA")).isFalse();
        assertThat(registry.getClient("partnerA")).isNull();
        assertThat(registry.getTemplate("partnerA")).isNull();
        assertThat(registry.unregister("partnerA")).isNull();
        assertThat(registry.unregister(null)).isNull();

        cm1.close();
        ctx.close();
    }
}
