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
}
