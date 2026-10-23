package io.github.atengk.boot.ssh.shell;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.model.SshShellOptions;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交互式 PTY 伪终端受管句柄端到端全真单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshShellHandle 伪终端句柄端到端单元测试")
class SshShellHandleTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private int serverPort;

    private final AtomicReference<String> serverTerm = new AtomicReference<>();
    private final AtomicReference<String> serverCols = new AtomicReference<>();
    private final AtomicReference<String> serverRows = new AtomicReference<>();
    private final AtomicInteger windowChangeCount = new AtomicInteger(0);

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        serverTerm.set(null);
        serverCols.set(null);
        serverRows.set(null);
        windowChangeCount.set(0);

        // 1. 启动嵌入式 SshServer，定制 ShellFactory 模拟真实 PTY 交互
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "shelluser".equals(username) && "shellpass".equals(password));

        sshServer.setShellFactory(channelSession -> new TestEchoShellCommand());
        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 启动客户端
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
    @DisplayName("测试建立 PTY 终端通道、双向二进制数据收发与字符串便捷输入")
    void testShellBidirectionalCommunication() throws Exception {
        SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "shelluser", "shellpass");
        try (ClientSession session = sshClient.connect("shelluser", "127.0.0.1", serverPort)
                .verify(Duration.ofSeconds(5)).getSession()) {
            session.addPasswordIdentity("shellpass");
            session.auth().verify(Duration.ofSeconds(5));

            ChannelShell channel = session.createShellChannel();
            SshShellOptions options = SshShellOptions.ofSize(90, 30);
            channel.setPtyType(options.getTerm());
            channel.setPtyColumns(options.getCols());
            channel.setPtyLines(options.getRows());

            ByteArrayOutputStream receivedBuffer = new ByteArrayOutputStream();
            CountDownLatch latch = new CountDownLatch(2);

            try (SshShellHandle handle = new SshShellHandle(serverInfo, channel, options, null)) {
                handle.onOutput(bytes -> {
                    synchronized (receivedBuffer) {
                        receivedBuffer.writeBytes(bytes);
                    }
                    latch.countDown();
                });

                channel.open().verify(Duration.ofSeconds(5));

                assertThat(handle.isAlive()).isTrue();
                assertThat(handle.isClosed()).isFalse();

                // 1. 发送二进制字节流
                byte[] binaryData = "PING_BYTE\n".getBytes(StandardCharsets.UTF_8);
                handle.sendInput(binaryData);

                // 2. 发送字符串便捷方法
                handle.sendInput("HELLO_STRING\n");

                awaitCondition(() -> {
                    String output = receivedBuffer.toString(StandardCharsets.UTF_8);
                    return output.contains("PING_BYTE") && output.contains("HELLO_STRING");
                }, 5000);

                String output = receivedBuffer.toString(StandardCharsets.UTF_8);
                assertThat(output).contains("PING_BYTE");
                assertThat(output).contains("HELLO_STRING");

                assertThat(serverTerm.get()).isEqualTo("xterm-256color");
                assertThat(serverCols.get()).isEqualTo("90");
                assertThat(serverRows.get()).isEqualTo("30");
            }
        }
    }

    @Test
    @DisplayName("测试动态窗口大小自适应调整 (resize) 与协议包发送")
    void testWindowResize() throws Exception {
        SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "shelluser", "shellpass");
        try (ClientSession session = sshClient.connect("shelluser", "127.0.0.1", serverPort)
                .verify(Duration.ofSeconds(5)).getSession()) {
            session.addPasswordIdentity("shellpass");
            session.auth().verify(Duration.ofSeconds(5));

            ChannelShell channel = session.createShellChannel();
            SshShellOptions options = SshShellOptions.defaults();
            channel.setPtyType(options.getTerm());
            channel.setPtyColumns(options.getCols());
            channel.setPtyLines(options.getRows());

            try (SshShellHandle handle = new SshShellHandle(serverInfo, channel, options, null)) {
                channel.open().verify(Duration.ofSeconds(5));

                awaitCondition(() -> serverCols.get() != null, 3000);

                assertThat(serverCols.get()).isEqualTo("80");
                assertThat(serverRows.get()).isEqualTo("24");

                // 发送 resize
                handle.resize(132, 43);

                // 等待服务端收到 WINCH 信号更新尺寸
                awaitCondition(() -> "132".equals(serverCols.get()) && "43".equals(serverRows.get()), 3000);

                assertThat(serverCols.get()).isEqualTo("132");
                assertThat(serverRows.get()).isEqualTo("43");
                assertThat(windowChangeCount.get()).isGreaterThanOrEqualTo(1);

                // 非法参数防卫
                assertThatThrownBy(() -> handle.resize(0, 40))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> handle.resize(100, -1))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @DisplayName("测试远端进程退出状态监听 (onExit) 与通道关闭监听 (onClose)")
    void testExitAndCloseListeners() throws Exception {
        SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "shelluser", "shellpass");
        try (ClientSession session = sshClient.connect("shelluser", "127.0.0.1", serverPort)
                .verify(Duration.ofSeconds(5)).getSession()) {
            session.addPasswordIdentity("shellpass");
            session.auth().verify(Duration.ofSeconds(5));

            ChannelShell channel = session.createShellChannel();
            SshShellOptions options = SshShellOptions.defaults();
            channel.setPtyType(options.getTerm());
            channel.setPtyColumns(options.getCols());
            channel.setPtyLines(options.getRows());

            AtomicInteger capturedExitCode = new AtomicInteger(-1);
            AtomicBoolean closeTriggered = new AtomicBoolean(false);
            CountDownLatch exitLatch = new CountDownLatch(1);
            CountDownLatch closeLatch = new CountDownLatch(1);

            SshShellHandle handle = new SshShellHandle(serverInfo, channel, options, null);
            handle.onExit(code -> {
                capturedExitCode.set(code);
                exitLatch.countDown();
            });
            handle.onClose(() -> {
                closeTriggered.set(true);
                closeLatch.countDown();
            });

            channel.open().verify(Duration.ofSeconds(5));

            // 发送让服务端退出的特殊指令
            handle.sendInput("exit\n");

            boolean exitCompleted = exitLatch.await(5, TimeUnit.SECONDS);
            assertThat(exitCompleted).isTrue();
            assertThat(capturedExitCode.get()).isZero();

            // 远端退出后关闭句柄，验证 onClose 回调触发
            handle.close();
            boolean closeCompleted = closeLatch.await(5, TimeUnit.SECONDS);
            assertThat(closeCompleted).isTrue();
            assertThat(closeTriggered.get()).isTrue();

            assertThat(handle.isAlive()).isFalse();
            assertThat(handle.isClosed()).isTrue();

            // 对已关闭的终端发送数据抛出异常
            assertThatThrownBy(() -> handle.sendInput("DATA"))
                    .isInstanceOf(SshOperationException.class)
                    .hasMessageContaining("SSH 终端通道已关闭");

            assertThatThrownBy(() -> handle.resize(100, 30))
                    .isInstanceOf(SshOperationException.class)
                    .hasMessageContaining("SSH 终端通道已关闭");
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /**
     * 模拟交互式 Shell 服务端命令
     */
    private class TestEchoShellCommand implements Command, SignalListener {

        private InputStream in;
        private OutputStream out;
        private ExitCallback callback;
        private Thread workerThread;
        private Environment environment;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            // 测试忽略单独错误流
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            this.environment = env;
            env.addSignalListener(this, Signal.WINCH);

            serverTerm.set(env.getEnv().get(Environment.ENV_TERM));
            serverCols.set(env.getEnv().get(Environment.ENV_COLUMNS));
            serverRows.set(env.getEnv().get(Environment.ENV_LINES));

            workerThread = new Thread(() -> {
                byte[] buf = new byte[1024];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        String str = new String(buf, 0, n, StandardCharsets.UTF_8);
                        if (str.contains("exit")) {
                            out.write("exit acknowledgement\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                            return;
                        }
                        out.write(buf, 0, n);
                        out.flush();
                    }
                    callback.onExit(0);
                } catch (IOException e) {
                    callback.onExit(1);
                }
            });
            workerThread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }

        @Override
        public void signal(org.apache.sshd.common.channel.Channel channel, Signal signal) {
            if (signal == Signal.WINCH && environment != null) {
                windowChangeCount.incrementAndGet();
                serverCols.set(environment.getEnv().get(Environment.ENV_COLUMNS));
                serverRows.set(environment.getEnv().get(Environment.ENV_LINES));
            }
        }
    }
}
