package io.github.atengk.boot.ssh.lifecycle;

import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshPoolProperties;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshLifecycleManager 三步确定性停机治理协调器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshLifecycleManager 三步停机治理单元测试")
class SshLifecycleManagerTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private int serverPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) -> "lifeuser".equals(username));
        sshServer.start();
        serverPort = sshServer.getPort();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (sshClient != null && sshClient.isStarted()) {
            sshClient.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试三步确定性停机治理：释放隧道 -> 注销会话池 -> 停止客户端引擎")
    void testDeterministicThreeStepShutdown() {
        SshSessionPool pool = new SshSessionPool(sshClient, new SshPoolProperties());
        SshLifecycleManager lifecycleManager = new SshLifecycleManager(pool, sshClient);

        lifecycleManager.start();
        assertThat(lifecycleManager.isRunning()).isTrue();

        // 1. 注册端口转发隧道模拟释放回调 (步骤 1)
        AtomicBoolean tunnelClosed = new AtomicBoolean(false);
        lifecycleManager.registerTunnelCleanupHook(() -> tunnelClosed.set(true));

        // 2. 借入一个活跃池化会话 (步骤 2)
        SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "lifeuser", "anypass");
        ClientSession session = pool.acquire(serverInfo);
        assertThat(session.isOpen()).isTrue();
        assertThat(pool.getActiveSessionCount()).isEqualTo(1);

        // 3. 触发停机时序
        lifecycleManager.stop();

        // 验证步骤 1：隧道回调已执行
        assertThat(tunnelClosed.get()).isTrue();
        // 验证步骤 2：会话池已关闭，活跃会话已物理断开
        assertThat(session.isOpen()).isFalse();
        assertThat(pool.getActiveSessionCount()).isEqualTo(0);
        // 验证步骤 3：底座 SshClient 引擎已停止
        assertThat(sshClient.isStarted()).isFalse();
        assertThat(lifecycleManager.isRunning()).isFalse();
    }
}
