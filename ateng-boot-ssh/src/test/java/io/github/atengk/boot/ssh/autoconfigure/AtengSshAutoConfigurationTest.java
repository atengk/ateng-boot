package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

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

    @Test
    @DisplayName("测试自动装配联动：SshClientRegistry 注销与覆盖节点自动驱逐 SshSessionPool 旧会话")
    void testRegistryUnregisterEvictsPooledSession(@TempDir Path tempDir) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((u, p, s) -> "ops".equals(u) && ("pass1".equals(p) || "pass2".equals(p)));
        server.start();
        try {
            int port = server.getPort();
            contextRunner.withPropertyValues(
                    "ateng.boot.ssh.servers.edge.host=127.0.0.1",
                    "ateng.boot.ssh.servers.edge.port=" + port,
                    "ateng.boot.ssh.servers.edge.username=ops",
                    "ateng.boot.ssh.servers.edge.password=pass1"
            ).run(context -> {
                SshClientRegistry registry = context.getBean(SshClientRegistry.class);
                SshSessionPool pool = context.getBean(SshSessionPool.class);

                SshServerInfo initialInfo = registry.getServer("edge");
                ClientSession session1 = pool.acquire(initialInfo);
                assertThat(session1.isOpen()).isTrue();
                assertThat(pool.getPoolSize()).isEqualTo(1);

                // 1. 覆盖注册（凭据轮换）：旧会话应被自动驱逐并物理关闭
                registry.register("edge", SshServerInfo.of("127.0.0.1", port, "ops", "pass2"));
                assertThat(session1.isOpen()).isFalse();
                assertThat(pool.getPoolSize()).isZero();

                // 2. 重新建连后调用 unregister：新会话也应被自动驱逐并物理关闭
                ClientSession session2 = pool.acquire(registry.getServer("edge"));
                assertThat(session2.isOpen()).isTrue();
                assertThat(pool.getPoolSize()).isEqualTo(1);

                SshServerInfo removed = registry.unregister("edge");
                assertThat(removed).isNotNull();
                assertThat(session2.isOpen()).isFalse();
                assertThat(pool.getPoolSize()).isZero();
            });
        } finally {
            server.stop(true);
        }
    }

    @Test
    @DisplayName("测试 YAML 声明式 jump-host 多级跳板机级联拓扑解析")
    void testYamlJumpHostMultiHopTopology() {
        contextRunner.withPropertyValues(
                "ateng.boot.ssh.host=10.20.0.10",
                "ateng.boot.ssh.username=root",
                "ateng.boot.ssh.password=rootpass",
                "ateng.boot.ssh.jump-host=bastion-outer",
                "ateng.boot.ssh.servers.bastion-outer.host=203.0.113.10",
                "ateng.boot.ssh.servers.bastion-outer.username=jump1",
                "ateng.boot.ssh.servers.bastion-outer.password=p1",
                "ateng.boot.ssh.servers.bastion-inner.host=172.16.0.10",
                "ateng.boot.ssh.servers.bastion-inner.username=jump2",
                "ateng.boot.ssh.servers.bastion-inner.password=p2",
                "ateng.boot.ssh.servers.bastion-inner.jump-host=bastion-outer",
                "ateng.boot.ssh.servers.app-internal.host=192.168.100.50",
                "ateng.boot.ssh.servers.app-internal.username=app",
                "ateng.boot.ssh.servers.app-internal.password=p3",
                "ateng.boot.ssh.servers.app-internal.jump-host=bastion-inner"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            SshClientRegistry registry = context.getBean(SshClientRegistry.class);

            SshServerInfo appInternal = registry.getServer("app-internal");
            assertThat(appInternal.hasJumpHost()).isTrue();
            assertThat(appInternal.getJumpHost().getHost()).isEqualTo("172.16.0.10");
            assertThat(appInternal.getJumpHost().hasJumpHost()).isTrue();
            assertThat(appInternal.getJumpHost().getJumpHost().getHost()).isEqualTo("203.0.113.10");
            assertThat(appInternal.getJumpHost().getJumpHost().hasJumpHost()).isFalse();

            SshServerInfo defaultServer = registry.getDefaultServer();
            assertThat(defaultServer.hasJumpHost()).isTrue();
            assertThat(defaultServer.getJumpHost().getHost()).isEqualTo("203.0.113.10");
        });
    }

    @Test
    @DisplayName("测试 YAML jump-host 自引用、循环引用与缺失节点 Fail-Fast 阻断")
    void testYamlJumpHostCycleAndMissingDetection() {
        // 1. 自引用 (nodeA -> nodeA)
        contextRunner.withPropertyValues(
                "ateng.boot.ssh.servers.node-a.host=10.0.0.1",
                "ateng.boot.ssh.servers.node-a.username=ops",
                "ateng.boot.ssh.servers.node-a.jump-host=node-a"
        ).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(failure).hasRootCauseInstanceOf(SshOperationException.class);
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
            assertThat(((SshOperationException) root).getResultCode()).isEqualTo(SshResultCode.JUMP_HOST_FAILED);
            assertThat(root.getMessage()).contains("node-a -> node-a");
        });

        // 2. 循环引用 (nodeA -> nodeB -> nodeA)
        contextRunner.withPropertyValues(
                "ateng.boot.ssh.servers.node-a.host=10.0.0.1",
                "ateng.boot.ssh.servers.node-a.username=ops",
                "ateng.boot.ssh.servers.node-a.jump-host=node-b",
                "ateng.boot.ssh.servers.node-b.host=10.0.0.2",
                "ateng.boot.ssh.servers.node-b.username=ops",
                "ateng.boot.ssh.servers.node-b.jump-host=node-a"
        ).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(SshOperationException.class);
            assertThat(((SshOperationException) root).getResultCode()).isEqualTo(SshResultCode.JUMP_HOST_FAILED);
            assertThat(root.getMessage()).contains("node-a -> node-b -> node-a");
        });

        // 3. 缺失节点引用 (nodeA -> non-existent)
        contextRunner.withPropertyValues(
                "ateng.boot.ssh.servers.node-a.host=10.0.0.1",
                "ateng.boot.ssh.servers.node-a.username=ops",
                "ateng.boot.ssh.servers.node-a.jump-host=non-existent"
        ).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(SshOperationException.class);
            assertThat(((SshOperationException) root).getResultCode()).isEqualTo(SshResultCode.JUMP_HOST_FAILED);
            assertThat(root.getMessage()).contains("non-existent");
        });
    }
}
