package io.github.atengk.boot.ssh.shell;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.model.SshShellOptions;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshTemplate openShell 对称门面与生命周期纳管单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshTemplate openShell 门面与生命周期联动测试")
class SshTemplateShellTest {

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
                "shelluser".equals(username) && "shellpass".equals(password));
        sshServer.setShellFactory(channelSession -> new MockShellCommand());
        sshServer.start();
        serverPort = sshServer.getPort();

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(serverPort);
        properties.setUsername("shelluser");
        properties.setPassword("shellpass");
        properties.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        registry = new SshClientRegistry();
        registry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(properties));

        SshServerInfo backupNode = SshServerInfo.of("127.0.0.1", serverPort, "shelluser", "shellpass");
        registry.register("backup-node", backupNode);

        sessionPool = new SshSessionPool(sshClient, properties.getPool());
        lifecycleManager = new SshLifecycleManager(sessionPool, sshClient);
        lifecycleManager.start();

        template = new SshTemplate(sessionPool, registry, properties, null, lifecycleManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        SshContextHolder.clear();
        if (lifecycleManager != null) {
            lifecycleManager.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试通过缺省节点 openShell 开启终端并验证自动生命周期注册与注销")
    void testDefaultOpenShellAndLifecycleTracking() throws Exception {
        assertThat(lifecycleManager.getActiveShells()).isEmpty();

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        SshShellHandle handle = template.openShell();
        try {
            assertThat(handle.isAlive()).isTrue();
            // 验证自动注册到生命周期管理器
            assertThat(lifecycleManager.getActiveShells()).contains(handle);

            handle.onOutput(bytes -> {
                buffer.writeBytes(bytes);
                latch.countDown();
            });

            handle.sendInput("hello\n");
            boolean ok = latch.await(5, TimeUnit.SECONDS);
            assertThat(ok).isTrue();
            assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("ECHO:hello");
        } finally {
            handle.close();
        }

        // 验证关闭后自动从生命周期管理器注销
        assertThat(lifecycleManager.getActiveShells()).doesNotContain(handle);
        assertThat(handle.isClosed()).isTrue();
    }

    @Test
    @DisplayName("测试通过具名节点与动态描述符开启携带选项的终端")
    void testNamedAndDynamicOpenShell() {
        // 1. 具名节点 openShell
        try (SshShellHandle namedHandle = template.openShell("backup-node", SshShellOptions.ofSize(100, 35))) {
            assertThat(namedHandle.isAlive()).isTrue();
            assertThat(namedHandle.getOptions().getCols()).isEqualTo(100);
            assertThat(namedHandle.getOptions().getRows()).isEqualTo(35);
            assertThat(lifecycleManager.getActiveShells()).contains(namedHandle);
        }

        // 2. 动态描述符直连 openShell
        SshServerInfo dynamicInfo = SshServerInfo.of("127.0.0.1", serverPort, "shelluser", "shellpass");
        try (SshShellHandle dynamicHandle = template.openShell(dynamicInfo)) {
            assertThat(dynamicHandle.isAlive()).isTrue();
            assertThat(dynamicHandle.getServerInfo().getHost()).isEqualTo("127.0.0.1");
            assertThat(lifecycleManager.getActiveShells()).contains(dynamicHandle);
        }
    }

    private static class MockShellCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback callback;
        private Thread thread;

        @Override
        public void setInputStream(InputStream in) { this.in = in; }
        @Override
        public void setOutputStream(OutputStream out) { this.out = out; }
        @Override
        public void setErrorStream(OutputStream err) {}
        @Override
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            thread = new Thread(() -> {
                byte[] buf = new byte[512];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                        out.write(("ECHO:" + s).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    callback.onExit(0);
                } catch (IOException e) {
                    callback.onExit(1);
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
