package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SSH 自动装配单元测试")
class AtengSshAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSshAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下成功装配全部 SSH 基础设施组件")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SshProperties.class);
            assertThat(context).hasSingleBean(ServerKeyVerifier.class);
            assertThat(context).hasSingleBean(SshClientRegistry.class);
            assertThat(context).hasSingleBean(SshClient.class);
            assertThat(context).hasSingleBean(SshSessionPool.class);
            assertThat(context).hasSingleBean(SshLifecycleManager.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.ssh.template.SshTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.ssh.template.SftpTemplate.class);

            SshClient client = context.getBean(SshClient.class);
            assertThat(client.isStarted()).isTrue();
            assertThat(client.getServerKeyVerifier()).isInstanceOf(InMemoryServerKeyVerifier.class);

            SshLifecycleManager lifecycleManager = context.getBean(SshLifecycleManager.class);
            assertThat(lifecycleManager.isRunning()).isTrue();
        });
    }

    @Test
    @DisplayName("测试通过 YAML 配置声明默认节点与命名多节点字典时正确注入注册表")
    void testRegistryPopulationFromProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.ssh.host=192.168.1.100",
                "ateng.boot.ssh.username=root",
                "ateng.boot.ssh.password=rootpass",
                "ateng.boot.ssh.servers.node-a.host=10.0.0.1",
                "ateng.boot.ssh.servers.node-a.username=deploy",
                "ateng.boot.ssh.servers.node-a.password=deploypass"
        ).run(context -> {
            assertThat(context).hasSingleBean(SshClientRegistry.class);
            SshClientRegistry registry = context.getBean(SshClientRegistry.class);

            assertThat(registry.contains("default")).isTrue();
            assertThat(registry.contains("node-a")).isTrue();

            SshServerInfo defaultServer = registry.getDefaultServer();
            assertThat(defaultServer.getHost()).isEqualTo("192.168.1.100");
            assertThat(defaultServer.getUsername()).isEqualTo("root");

            SshServerInfo nodeA = registry.getServer("node-a");
            assertThat(nodeA.getHost()).isEqualTo("10.0.0.1");
            assertThat(nodeA.getUsername()).isEqualTo("deploy");
        });
    }

    @Test
    @DisplayName("测试当 enabled=false 时不触发任何 SSH 设施自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.ssh.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SshProperties.class);
                    assertThat(context).doesNotHaveBean(ServerKeyVerifier.class);
                    assertThat(context).doesNotHaveBean(SshClientRegistry.class);
                    assertThat(context).doesNotHaveBean(SshClient.class);
                    assertThat(context).doesNotHaveBean(SshSessionPool.class);
                    assertThat(context).doesNotHaveBean(SshLifecycleManager.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.ssh.template.SshTemplate.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 SshClient 与 ServerKeyVerifier Bean 时能够平滑接管")
    void testCustomBeanOverride() {
        ServerKeyVerifier customVerifier = AcceptAllServerKeyVerifier.INSTANCE;
        SshClient customClient = SshClient.setUpDefaultClient();

        contextRunner
                .withBean("serverKeyVerifier", ServerKeyVerifier.class, () -> customVerifier)
                .withBean("sshClient", SshClient.class, () -> customClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(ServerKeyVerifier.class);
                    assertThat(context.getBean(ServerKeyVerifier.class)).isSameAs(customVerifier);

                    assertThat(context).hasSingleBean(SshClient.class);
                    assertThat(context.getBean(SshClient.class)).isSameAs(customClient);
                });
    }

    @Test
    @DisplayName("测试当容器存在 taskExecutor 时平滑注入 SshTemplate")
    void testTaskExecutorInjection() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.initialize();
        try {
            contextRunner
                    .withBean("taskExecutor", java.util.concurrent.Executor.class, () -> executor)
                    .run(context -> {
                        assertThat(context).hasSingleBean(io.github.atengk.boot.ssh.template.SshTemplate.class);
                    });
        } finally {
            executor.shutdown();
        }
    }
}
