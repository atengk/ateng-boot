package io.github.atengk.boot.ai.autoconfigure;

import io.github.atengk.boot.ai.mcp.HttpMcpClientAdapter;
import io.github.atengk.boot.ai.mcp.McpClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 远程 MCP 客户端注册中心自动装配单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("MCP 客户端注册中心自动装配单元测试")
class McpAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiAutoConfiguration.class));

    @Test
    @DisplayName("默认启用且无配置 server 时成功装配空 McpClientRegistry")
    void testDefaultMcpClientRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(McpClientRegistry.class);
            McpClientRegistry registry = context.getBean(McpClientRegistry.class);
            assertThat(registry.isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.ai.mcp.servers 时成功初始化并注册 HttpMcpClientAdapter")
    void testConfiguredMcpServers() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.ai.mcp.enabled=true",
                        "ateng.boot.ai.mcp.servers.weather.url=http://localhost:8080/mcp",
                        "ateng.boot.ai.mcp.servers.weather.eager-init=false",
                        "ateng.boot.ai.mcp.servers.weather.api-key=test-token"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(McpClientRegistry.class);
                    McpClientRegistry registry = context.getBean(McpClientRegistry.class);
                    assertThat(registry.contains("weather")).isTrue();
                    assertThat(registry.getAdapter("weather")).isInstanceOf(HttpMcpClientAdapter.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.ai.mcp.enabled=false 时不装配 McpClientRegistry")
    void testMcpDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.ai.mcp.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(McpClientRegistry.class);
                });
    }
}
