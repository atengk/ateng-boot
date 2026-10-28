package io.github.atengk.boot.ssh.shell;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshLifecycleManager 停机第一阶段自动排空活跃 PTY 终端单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshLifecycleManager 停机终端排空单元测试")
class SshLifecycleManagerShellTeardownTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry registry;
    private SshLifecycleManager lifecycleManager;
    private SshTemplate template;
    private int serverPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "teardownuser".equals(username) && "teardownpass".equals(password));
        sshServer.setShellFactory(channelSession -> new SleepShellCommand());
        sshServer.start();
        serverPort = sshServer.getPort();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(serverPort);
        properties.setUsername("teardownuser");
        properties.setPassword("teardownpass");
        properties.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        registry = new SshClientRegistry();
        registry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(properties));

        sessionPool = new SshSessionPool(sshClient, properties.getPool());
        lifecycleManager = new SshLifecycleManager(sessionPool, sshClient);
        lifecycleManager.start();

        template = new SshTemplate(sessionPool, registry, properties, null, lifecycleManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (lifecycleManager != null && lifecycleManager.isRunning()) {
            lifecycleManager.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试停机时自动排空关闭所有活跃 Shell 终端句柄")
    void testShellTeardownOnLifecycleStop() {
        // 打开 3 个活跃的交互式伪终端
        SshShellHandle shell1 = template.openShell();
        SshShellHandle shell2 = template.openShell();
        SshShellHandle shell3 = template.openShell();

        assertThat(shell1.isAlive()).isTrue();
        assertThat(shell2.isAlive()).isTrue();
        assertThat(shell3.isAlive()).isTrue();
        assertThat(lifecycleManager.getActiveShells()).hasSize(3);

        // 执行生命周期停机
        lifecycleManager.stop();

        // 验证 3 个终端均已确定性物理关闭
        assertThat(shell1.isClosed()).isTrue();
        assertThat(shell2.isClosed()).isTrue();
        assertThat(shell3.isClosed()).isTrue();
        assertThat(shell1.isAlive()).isFalse();
        assertThat(shell2.isAlive()).isFalse();
        assertThat(shell3.isAlive()).isFalse();
        assertThat(lifecycleManager.getActiveShells()).isEmpty();
    }

    private static class SleepShellCommand implements Command {
        private ExitCallback callback;
        private Thread thread;

        @Override
        public void setInputStream(InputStream in) {}
        @Override
        public void setOutputStream(OutputStream out) {}
        @Override
        public void setErrorStream(OutputStream err) {}
        @Override
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            thread = new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    callback.onExit(0);
                } catch (InterruptedException e) {
                    callback.onExit(130);
                }
            });
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
