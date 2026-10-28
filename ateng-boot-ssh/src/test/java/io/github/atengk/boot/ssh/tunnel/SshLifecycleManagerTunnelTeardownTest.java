package io.github.atengk.boot.ssh.tunnel;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshLifecycleManager 停机阶段端口转发隧道受管清理单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshLifecycleManager 端口转发停机清理单元测试")
class SshLifecycleManagerTunnelTeardownTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshLifecycleManager lifecycleManager;
    private SshTemplate template;
    private ServerSocket dummySocket;
    private int dummyPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        dummySocket = new ServerSocket(0);
        dummyPort = dummySocket.getLocalPort();

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password));
        sshServer.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshServer.start();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(sshServer.getPort());
        properties.setUsername("testuser");
        properties.setPassword("testpass");
        properties.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        SshClientRegistry registry = new SshClientRegistry();
        registry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(properties));

        sessionPool = new SshSessionPool(sshClient, properties.getPool());
        lifecycleManager = new SshLifecycleManager(sessionPool, sshClient);
        lifecycleManager.start();

        template = new SshTemplate(sessionPool, registry, properties, null, lifecycleManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dummySocket != null && !dummySocket.isClosed()) {
            dummySocket.close();
        }
        if (lifecycleManager != null && lifecycleManager.isRunning()) {
            lifecycleManager.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试应用优雅停机：未手动close的活跃隧道被生命周期协调器优先全部关闭")
    void testShutdownLifecycleTearDown() {
        // 打开两个受管隧道，均不显式 close
        SshTunnelHandle tunnel1 = template.openLocalTunnel(0, "127.0.0.1", dummyPort);
        SshTunnelHandle tunnel2 = template.openLocalTunnel(0, "127.0.0.1", dummyPort);

        assertThat(tunnel1.isAlive()).isTrue();
        assertThat(tunnel2.isAlive()).isTrue();
        assertThat(lifecycleManager.getActiveTunnels()).contains(tunnel1, tunnel2);

        // 触发容器停机
        lifecycleManager.stop();

        // 验证生命周期状态与隧道确定性释放
        assertThat(lifecycleManager.isRunning()).isFalse();
        assertThat(tunnel1.isAlive()).isFalse();
        assertThat(tunnel1.isClosed()).isTrue();
        assertThat(tunnel2.isAlive()).isFalse();
        assertThat(tunnel2.isClosed()).isTrue();
        assertThat(lifecycleManager.getActiveTunnels()).isEmpty();
    }
}
