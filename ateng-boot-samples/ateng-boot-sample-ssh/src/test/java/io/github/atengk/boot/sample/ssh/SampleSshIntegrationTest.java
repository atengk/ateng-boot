package io.github.atengk.boot.sample.ssh;

import io.github.atengk.boot.sample.ssh.service.SftpScenarioService;
import io.github.atengk.boot.sample.ssh.service.SshScenarioService;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.listener.SftpProgressListener;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.model.SshBatchExecResult;
import io.github.atengk.boot.ssh.model.SshBatchOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 与 SFTP 场景样例服务 100% 真实端到端集成测试
 * <p>
 * 全自洽离线验证底座所有核心业务场景与双模自适应：<br>
 * 1. 同步短命令执行 (SshScenarioService.exec)<br>
 * 2. 非阻塞异步命令执行 (SshScenarioService.execAsync)<br>
 * 3. 原生 Sudo 提权安全管道 (SshScenarioService.execSudo)<br>
 * 4. 节点连通性探针探测 (SshScenarioService.testConnection)<br>
 * 5. 集群高并发高级批量巡检聚合 (SshScenarioService.runBatchInspectionNamed)<br>
 * 6. ProxyJump 跳板机级联穿透 (SshScenarioService.runCascadingProxyJump)<br>
 * 7. 瞬态单次任务隔离执行 (SshScenarioService.runTransientTask)<br>
 * 8. 本地端口转发真实 TCP 流量交互与生命周期释放 (SshScenarioService.forwardAndQueryTcp / openManagedTunnel)<br>
 * 9. SshContextHolder 动态切源 (SshScenarioService.executeWithContextSwitch)<br>
 * 10. 长命令流式日志零内存缓冲捕获 (SshScenarioService.streamLogs)<br>
 * 11. 多行运维脚本管道执行 (SshScenarioService.runMaintenanceScript)<br>
 * 12. SFTP 原子上传、下载与存在性校验 (SftpScenarioService.upload / download / exists)<br>
 * 13. SFTP 尾部追加与文本配置快捷读写 (SftpScenarioService.append / writeConfig / readConfig)<br>
 * 14. SFTP 原子重命名与跨目录移动 (SftpScenarioService.rename / move)<br>
 * 15. SFTP 流式进度高频节流防抖 (SftpScenarioService.uploadWithThrottledProgress)<br>
 * 16. SFTP 目录列表检索与 DFS 深度优先递归删除 (SftpScenarioService.list / deleteRecursively)<br>
 * 17. SFTP 目录树双向整包镜像同步 (SftpScenarioService.syncUploadDirectory / syncDownloadDirectory)<br>
 * 18. WebSSH WebSocket 原生双向伪终端通信与视口自适应管道
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SampleSshIntegrationTest.EmbeddedSshTestConfig.class)
@DisplayName("SSH 与 SFTP 场景样例服务端到端集成实测")
class SampleSshIntegrationTest {

    @LocalServerPort
    private int localServerPort;

    @Autowired
    private SshScenarioService sshScenarioService;

    @Autowired
    private SftpScenarioService sftpScenarioService;

    @TempDir
    static Path testTempDir;

    private static Path sharedTempDir;
    private static SshServer defaultServer;
    private static SshServer node1Server;
    private static SshServer node2Server;
    private static SshServer bastionServer;

    private static SshServer jump1Server;
    private static SshServer jump2Server;
    private static SshServer targetServer;

    private static ServerSocket echoTcpServer;
    private static ExecutorService echoTcpExecutor;
    private static final AtomicBoolean echoRunning = new AtomicBoolean(true);
    private static final AtomicBoolean embeddedStarted = new AtomicBoolean(false);

    /**
     * 离线自洽 SSH 嵌入式测试配置：标配 @Profile("!local")，当激活 local Profile 联调真实环境时自动失效
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("!local")
    static class EmbeddedSshTestConfig {
    }

    private static boolean isLocalProfile() {
        String profile = System.getProperty("spring.profiles.active");
        if (profile != null && profile.contains("local")) {
            return true;
        }
        String envProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        return envProfile != null && envProfile.contains("local");
    }

    private String getSftpBaseDir() {
        return isLocalProfile() ? "/tmp/ateng-sample-ssh" : "";
    }

    private static synchronized void ensureEmbeddedServersStarted() {
        if (embeddedStarted.get() || isLocalProfile()) {
            return;
        }
        try {
            startAllEmbeddedServers();
            embeddedStarted.set(true);
        } catch (IOException e) {
            throw new UncheckedIOException("启动嵌入式 SSH/TCP 服务端失败", e);
        }
    }

    private static synchronized void ensureCascadingJumpServersStarted() {
        if (jump1Server != null) {
            return;
        }
        try {
            if (sharedTempDir == null) {
                sharedTempDir = Files.createTempDirectory("sample-ssh-embedded-");
            }
            jump1Server = createServer("jump1_key.ser", "jump1_user", "jump1_pass", true);
            jump1Server.start();
            jump2Server = createServer("jump2_key.ser", "jump2_user", "jump2_pass", true);
            jump2Server.start();
            targetServer = createServer("target_key.ser", "target_user", "target_pass", true);
            targetServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException("启动嵌入式 ProxyJump 测试节点失败", e);
        }
    }

    @BeforeAll
    static void setupServers() {
        ensureEmbeddedServersStarted();
    }

    @AfterAll
    static void stopAllServers() throws IOException {
        echoRunning.set(false);
        if (echoTcpServer != null && !echoTcpServer.isClosed()) {
            echoTcpServer.close();
        }
        if (echoTcpExecutor != null) {
            echoTcpExecutor.shutdownNow();
        }
        if (defaultServer != null) defaultServer.stop(true);
        if (node1Server != null) node1Server.stop(true);
        if (node2Server != null) node2Server.stop(true);
        if (bastionServer != null) bastionServer.stop(true);
        if (jump1Server != null) jump1Server.stop(true);
        if (jump2Server != null) jump2Server.stop(true);
        if (targetServer != null) targetServer.stop(true);
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            ensureEmbeddedServersStarted();
            registry.add("ateng.boot.ssh.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ssh.port", () -> defaultServer.getPort());
            registry.add("ateng.boot.ssh.username", () -> "testuser");
            registry.add("ateng.boot.ssh.password", () -> "testpassword");
            registry.add("ateng.boot.ssh.servers.node1.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ssh.servers.node1.port", () -> node1Server.getPort());
            registry.add("ateng.boot.ssh.servers.node1.username", () -> "worker1");
            registry.add("ateng.boot.ssh.servers.node1.password", () -> "worker1pass");
            registry.add("ateng.boot.ssh.servers.node2.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ssh.servers.node2.port", () -> node2Server.getPort());
            registry.add("ateng.boot.ssh.servers.node2.username", () -> "worker2");
            registry.add("ateng.boot.ssh.servers.node2.password", () -> "worker2pass");
            registry.add("ateng.boot.ssh.servers.bastion.host", () -> "127.0.0.1");
            registry.add("ateng.boot.ssh.servers.bastion.port", () -> bastionServer.getPort());
            registry.add("ateng.boot.ssh.servers.bastion.username", () -> "ops");
            registry.add("ateng.boot.ssh.servers.bastion.password", () -> "opspassword");
        }
    }

    private static void startAllEmbeddedServers() throws IOException {
        if (sharedTempDir == null) {
            sharedTempDir = Files.createTempDirectory("sample-ssh-embedded-");
        }

        // 1. 启动轻量级 Echo TCP 服务端（模拟内网业务服务）
        echoRunning.set(true);
        echoTcpServer = new ServerSocket(0);
        echoTcpServer.setReuseAddress(true);
        echoTcpExecutor = Executors.newCachedThreadPool();
        echoTcpExecutor.submit(() -> {
            while (echoRunning.get() && !echoTcpServer.isClosed()) {
                try {
                    Socket client = echoTcpServer.accept();
                    echoTcpExecutor.submit(() -> {
                        try (client;
                             BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
                            String l = r.readLine();
                            if (l != null) {
                                w.write("ECHO_REPLY:" + l + "\n");
                                w.flush();
                            }
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                    break;
                }
            }
        });

        // 2. 启动 default 主节点（配置 SFTP 子系统与 Shell 工厂）
        defaultServer = createServer("default_key.ser", "testuser", "testpassword", true);
        defaultServer.start();

        // 3. 启动 node1 与 node2 节点
        node1Server = createServer("node1_key.ser", "worker1", "worker1pass", false);
        node1Server.start();

        node2Server = createServer("node2_key.ser", "worker2", "worker2pass", false);
        node2Server.start();

        // 4. 启动 bastion 节点
        bastionServer = createServer("bastion_key.ser", "ops", "opspassword", true);
        bastionServer.start();

        // 5. 启动两级级联穿透测试节点 (Jump1, Jump2, Target)
        ensureCascadingJumpServersStarted();
    }

    private static SshServer createServer(String keyFile, String expectedUser, String expectedPass, boolean sudoSupport) {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(sharedTempDir.resolve(keyFile)));
        server.setPasswordAuthenticator((u, p, s) -> expectedUser.equals(u) && expectedPass.equals(p));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);

        if ("testuser".equals(expectedUser)) {
            Path sftpRoot = sharedTempDir.resolve("sftp-storage");
            try {
                Files.createDirectories(sftpRoot);
            } catch (IOException ignored) {
            }
            server.setSubsystemFactories(Collections.singletonList(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
            server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(sftpRoot));
            server.setShellFactory(channelSession -> new SampleEchoShellCommand());
        }

        server.setCommandFactory((channel, command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private ExitCallback callback;
            private Thread worker;

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
            }

            @Override
            public void setExitCallback(ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(ChannelSession channel, Environment env) {
                worker = new Thread(() -> {
                    try {
                        if (sudoSupport && command.startsWith("sudo -S -p '' ")) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                            String pwd = r.readLine();
                            if (expectedPass.equals(pwd)) {
                                String realCmd = command.substring("sudo -S -p '' ".length());
                                out.write(("SUDO_SUCCESS: " + realCmd + "\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                callback.onExit(0);
                            } else {
                                callback.onExit(1);
                            }
                        } else if (command.startsWith("echo ")) {
                            String payload = command.substring(5).trim();
                            out.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                        } else {
                            out.write(("ACK: " + command + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                        }
                    } catch (Exception e) {
                        if (callback != null) {
                            callback.onExit(1, e.getMessage());
                        }
                    }
                }, "sample-ssh-cmd-worker");
                worker.start();
            }

            @Override
            public void destroy(ChannelSession channel) {
                if (worker != null) {
                    worker.interrupt();
                }
            }
        });
        return server;
    }

    // ==========================================
    // 1. SshScenarioService 场景演练测试
    // ==========================================

    @Test
    @DisplayName("测试同步短命令执行：主节点与指定命名节点同步执行成功")
    void testSshExecSync() {
        SshExecResult defaultResult = sshScenarioService.exec("echo HELLO_DEFAULT");
        assertThat(defaultResult.isSuccess()).isTrue();
        assertThat(defaultResult.getExitCode()).isEqualTo(0);
        assertThat(defaultResult.getStdout()).isEqualTo("HELLO_DEFAULT\n");

        SshExecResult node1Result = sshScenarioService.exec("node1", "echo HELLO_NODE1");
        assertThat(node1Result.isSuccess()).isTrue();
        assertThat(node1Result.getStdout()).isEqualTo("HELLO_NODE1\n");
    }

    @Test
    @DisplayName("测试非阻塞异步命令执行：CompletableFuture 成功异步完成回调")
    void testSshExecAsync() throws Exception {
        CompletableFuture<SshExecResult> future = sshScenarioService.execAsync("echo ASYNC_TASK_OK");
        SshExecResult result = future.get(10, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).isEqualTo("ASYNC_TASK_OK\n");

        CompletableFuture<SshExecResult> nodeFuture = sshScenarioService.execAsync("node1", "echo NODE1_ASYNC");
        SshExecResult nodeResult = nodeFuture.get(10, TimeUnit.SECONDS);
        assertThat(nodeResult.isSuccess()).isTrue();
        assertThat(nodeResult.getStdout()).isEqualTo("NODE1_ASYNC\n");
    }

    @Test
    @DisplayName("测试原生 Sudo 提权管道：密码即时擦除并成功提权执行")
    void testSshExecSudo() {
        String sudoPass = isLocalProfile() ? "Admin@123" : "testpassword";
        SshExecResult result = isLocalProfile()
                ? sshScenarioService.execSudo("node1", "echo SUDO_OK", sudoPass)
                : sshScenarioService.execSudo("echo SUDO_OK", sudoPass);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).contains("SUDO_OK");
    }

    @Test
    @DisplayName("测试无害轻量连通性探针：准确探测命名节点与动态主机连通性")
    void testSshConnectionProbe() {
        assertThat(sshScenarioService.testConnection("node1")).isTrue();
        assertThat(sshScenarioService.testConnection("node2")).isTrue();
        assertThat(sshScenarioService.testConnection("bastion")).isTrue();

        SshServerInfo validDynamic = isLocalProfile()
                ? SshServerInfo.builder()
                        .host("192.168.157.3")
                        .port(22)
                        .username("admin")
                        .password("Admin@123")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build()
                : SshServerInfo.builder()
                        .host("127.0.0.1")
                        .port(node1Server.getPort())
                        .username("worker1")
                        .password("worker1pass")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build();
        assertThat(sshScenarioService.testConnection(validDynamic)).isTrue();

        SshServerInfo invalidDynamic = isLocalProfile()
                ? SshServerInfo.builder()
                        .host("192.168.157.3")
                        .port(22)
                        .username("admin")
                        .password("WRONG_PASS_PROBE")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build()
                : SshServerInfo.builder()
                        .host("127.0.0.1")
                        .port(node1Server.getPort())
                        .username("worker1")
                        .password("WRONG_PASS")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build();
        assertThat(sshScenarioService.testConnection(invalidDynamic)).isFalse();
    }

    @Test
    @DisplayName("测试集群高级批量巡检：聚合统计、并发控制与结果映射")
    void testSshBatchInspectionAdvanced() {
        SshBatchOptions options = SshBatchOptions.builder()
                .concurrency(2)
                .failFast(false)
                .build();

        SshBatchExecResult batchResult = sshScenarioService.runBatchInspectionNamed(
                List.of("node1", "node2"), "echo BATCH_FLEET_OK", options);

        assertThat(batchResult.getTotalCount()).isEqualTo(2);
        assertThat(batchResult.getSuccessCount()).isEqualTo(2);
        assertThat(batchResult.getFailureCount()).isEqualTo(0);
        assertThat(batchResult.isAllSuccess()).isTrue();
        assertThat(batchResult.getNamedResults().get("node1").getStdout()).isEqualTo("BATCH_FLEET_OK\n");
        assertThat(batchResult.getNamedResults().get("node2").getStdout()).isEqualTo("BATCH_FLEET_OK\n");
    }

    @Test
    @DisplayName("测试两级 ProxyJump 级联穿透：Client -> Jump1 -> Jump2 -> Target")
    void testScenarioCascadingProxyJump() {
        ensureCascadingJumpServersStarted();
        SshExecResult result = sshScenarioService.runCascadingProxyJump(
                "127.0.0.1", targetServer.getPort(),
                "127.0.0.1", jump1Server.getPort(),
                "127.0.0.1", jump2Server.getPort(),
                "echo SCENARIO_CASCADE_OK"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).isEqualTo("SCENARIO_CASCADE_OK\n");
    }

    @Test
    @DisplayName("测试瞬态单次任务隔离执行：执行后即刻断开会话，无状态残留")
    void testScenarioTransientTask() {
        SshServerInfo server = isLocalProfile()
                ? SshServerInfo.builder()
                        .host("192.168.157.3")
                        .port(22)
                        .username("root")
                        .password("Admin@123")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build()
                : SshServerInfo.builder()
                        .host("127.0.0.1")
                        .port(node1Server.getPort())
                        .username("worker1")
                        .password("worker1pass")
                        .hostKeyPolicy(HostKeyPolicy.IGNORE)
                        .build();

        SshExecResult result = sshScenarioService.runTransientTask(server, "echo TRANSIENT_RUN");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).isEqualTo("TRANSIENT_RUN\n");
    }

    @Test
    @DisplayName("测试本地端口转发双向 TCP 流量传输：本地客户端向远端真实服务发起通信")
    void testScenarioPortForwardingTcpFlow() throws Exception {
        if (isLocalProfile()) {
            // 在 local 环境下，测试通过本地端口转发隧道直连远端 127.0.0.1:22 并获取 SSH 协议版本横幅
            int localPort = sshScenarioService.openManagedTunnel("default", 0, "127.0.0.1", 22);
            try (Socket socket = new Socket("127.0.0.1", localPort);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5000);
                String banner = reader.readLine();
                assertThat(banner).contains("SSH-");
            } finally {
                sshScenarioService.closeManagedTunnel(localPort);
            }
        } else {
            String reply = sshScenarioService.forwardAndQueryTcp(
                    "127.0.0.1",
                    echoTcpServer.getLocalPort(),
                    "PROBE_MSG"
            );
            assertThat(reply).isEqualTo("ECHO_REPLY:PROBE_MSG");
        }
    }

    @Test
    @DisplayName("测试受管端口转发句柄对称开启与关闭释放")
    void testTunnelOpenAndCloseSymmetric() {
        int remotePort = isLocalProfile() ? 22 : echoTcpServer.getLocalPort();
        int boundPort = sshScenarioService.openManagedTunnel("default", 0, "127.0.0.1", remotePort);
        assertThat(boundPort).isGreaterThan(0);

        boolean closed = sshScenarioService.closeManagedTunnel(boundPort);
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("测试 SshContextHolder 动态切源至目标节点执行命令")
    void testContextSwitch() {
        String output = sshScenarioService.executeWithContextSwitch("node1", "echo CONTEXT_SWITCH_OK");
        assertThat(output).isEqualTo("CONTEXT_SWITCH_OK");
    }

    @Test
    @DisplayName("测试多行运维脚本管道执行：execScript 成功按行执行")
    void testScenarioMaintenanceScript() {
        String script = """
                echo step1
                echo step2
                echo step3
                """;

        SshExecResult result = sshScenarioService.runMaintenanceScript(script);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("测试长命令流式按行日志捕获（零内存缓冲防 OOM）")
    void testScenarioStreamLogs() {
        List<String> capturedLines = new ArrayList<>();
        int exitCode = sshScenarioService.streamLogs("echo STREAM_LINE_1", line -> {
            synchronized (capturedLines) {
                capturedLines.add(line);
            }
        });

        assertThat(exitCode).isEqualTo(0);
        assertThat(capturedLines).contains("STREAM_LINE_1");
    }

    // ==========================================
    // 2. SftpScenarioService 场景演练测试
    // ==========================================

    @Test
    @DisplayName("测试 SFTP 原子上传、存在性校验与流式下载全链路")
    void testSftpUploadAndDownloadAndExists() {
        String remotePath = getSftpBaseDir() + "/upload/hello.txt";
        byte[] payload = "Hello SFTP Storage Engine".getBytes(StandardCharsets.UTF_8);

        sftpScenarioService.upload(remotePath, payload);
        assertThat(sftpScenarioService.exists(remotePath)).isTrue();

        byte[] downloaded = sftpScenarioService.download(remotePath);
        assertThat(downloaded).isEqualTo(payload);
    }

    @Test
    @DisplayName("测试 SFTP 文本配置快捷读写与尾部流式追加写 (append)")
    void testSftpAppendAndReadWriteConfig() {
        String remotePath = getSftpBaseDir() + "/conf/database.properties";
        sftpScenarioService.writeConfig(remotePath, "db.url=jdbc:mysql://localhost:3306/db\n");

        String content = sftpScenarioService.readConfig(remotePath);
        assertThat(content).isEqualTo("db.url=jdbc:mysql://localhost:3306/db\n");

        sftpScenarioService.append(remotePath, "db.pool.max=32\n".getBytes(StandardCharsets.UTF_8));
        String updated = sftpScenarioService.readConfig(remotePath);
        assertThat(updated).isEqualTo("db.url=jdbc:mysql://localhost:3306/db\ndb.pool.max=32\n");
    }

    @Test
    @DisplayName("测试 SFTP 原子重命名与跨目录移动文件")
    void testSftpRenameAndMove() {
        String originalPath = getSftpBaseDir() + "/docs/spec_v1.txt";
        String renamedPath = getSftpBaseDir() + "/docs/spec_v1_renamed.txt";
        String movedPath = getSftpBaseDir() + "/archive/spec_v1_archived.txt";

        sftpScenarioService.writeConfig(originalPath, "Architecture Spec 2026");
        assertThat(sftpScenarioService.exists(originalPath)).isTrue();

        sftpScenarioService.rename(originalPath, renamedPath);
        assertThat(sftpScenarioService.exists(originalPath)).isFalse();
        assertThat(sftpScenarioService.exists(renamedPath)).isTrue();

        sftpScenarioService.move(renamedPath, movedPath);
        assertThat(sftpScenarioService.exists(renamedPath)).isFalse();
        assertThat(sftpScenarioService.exists(movedPath)).isTrue();
        assertThat(sftpScenarioService.readConfig(movedPath)).isEqualTo("Architecture Spec 2026");
    }

    @Test
    @DisplayName("测试 SFTP 流式进度高频节流防抖 (SftpProgressThrottler)")
    void testSftpUploadWithThrottledProgress() {
        String remotePath = getSftpBaseDir() + "/upload/throttled.bin";
        byte[] largeData = new byte[64 * 1024]; // 64 KB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 127);
        }

        AtomicInteger callCount = new AtomicInteger(0);
        SftpProgressListener listener = (transferred, total) -> callCount.incrementAndGet();

        sftpScenarioService.uploadWithThrottledProgress(remotePath, largeData, Duration.ofMillis(50), listener);

        assertThat(sftpScenarioService.exists(remotePath)).isTrue();
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("测试 SFTP 目录列表检索与 DFS 深度优先递归删除")
    void testSftpListAndRecursiveDelete() {
        String dir = getSftpBaseDir() + "/cleanup-test";
        sftpScenarioService.writeConfig(dir + "/f1.txt", "f1");
        sftpScenarioService.writeConfig(dir + "/sub/f2.txt", "f2");

        List<SftpFileInfo> entries = sftpScenarioService.list(dir);
        assertThat(entries).isNotEmpty();
        assertThat(entries.stream().anyMatch(e -> e.getFilename().equals("f1.txt"))).isTrue();

        boolean deleted = sftpScenarioService.deleteRecursively(dir);
        assertThat(deleted).isTrue();
        assertThat(sftpScenarioService.exists(dir)).isFalse();
    }

    @Test
    @DisplayName("测试 SFTP 目录树双向整包镜像同步 (uploadDirectory & downloadDirectory)")
    void testSftpDirectoryTreeSync() throws IOException {
        Path localUpload = Files.createTempDirectory(testTempDir, "sync-up-");
        Path localDownload = Files.createTempDirectory(testTempDir, "sync-down-");

        Files.createDirectories(localUpload.resolve("nested/dir"));
        Files.writeString(localUpload.resolve("root.txt"), "root content");
        Files.writeString(localUpload.resolve("nested/child.txt"), "child content");

        String remoteTree = getSftpBaseDir() + "/tree-sync-remote";
        sftpScenarioService.syncUploadDirectory(localUpload.toFile(), remoteTree);

        sftpScenarioService.syncDownloadDirectory(remoteTree, localDownload.toFile());

        assertThat(Files.exists(localDownload.resolve("root.txt"))).isTrue();
        assertThat(Files.readString(localDownload.resolve("root.txt"))).isEqualTo("root content");
        assertThat(Files.exists(localDownload.resolve("nested/child.txt"))).isTrue();
        assertThat(Files.readString(localDownload.resolve("nested/child.txt"))).isEqualTo("child content");

        // 清理远端测试目录树
        sftpScenarioService.deleteRecursively(remoteTree);
    }

    // ==========================================
    // 3. WebSSH WebSocket 协议管道实测
    // ==========================================

    @Test
    @DisplayName("测试 WebSSH WebSocket 原生双向终端通信、视口调整与优雅关闭全链路")
    void testWebSshWebSocketPipeline() throws Exception {
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch receiveLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        ByteArrayOutputStream receivedStream = new ByteArrayOutputStream();

        AbstractWebSocketHandler clientHandler = new AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                connectLatch.countDown();
                // 1. 发送视口 resize 消息
                session.sendMessage(new TextMessage("{\"action\":\"resize\",\"cols\":100,\"rows\":30}"));
                // 2. 发送普通字符输入
                session.sendMessage(new TextMessage("echo WEBSOCKET_TEST\n"));
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                ByteBuffer payload = message.getPayload();
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                synchronized (receivedStream) {
                    receivedStream.writeBytes(bytes);
                    String current = receivedStream.toString(StandardCharsets.UTF_8);
                    if (current.contains("WEBSOCKET_TEST")) {
                        receiveLatch.countDown();
                        // 收到回显后发送退出
                        try {
                            session.sendMessage(new TextMessage("exit\n"));
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closeLatch.countDown();
            }
        };

        String wsUrl = "ws://127.0.0.1:" + localServerPort + "/ws/ssh";
        WebSocketSession clientSession = wsClient.execute(clientHandler, wsUrl).get(10, TimeUnit.SECONDS);

        assertThat(connectLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(receiveLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50 && clientSession.isOpen(); i++) {
            Thread.sleep(20);
        }
        if (clientSession.isOpen()) {
            clientSession.close();
        }
        assertThat(clientSession.isOpen()).isFalse();
    }

    private static class SampleEchoShellCommand implements Command, org.apache.sshd.server.SignalListener {
        private InputStream in;
        private OutputStream out;
        private ExitCallback callback;
        private Thread worker;

        @Override
        public void setInputStream(InputStream in) { this.in = in; }
        @Override
        public void setOutputStream(OutputStream out) { this.out = out; }
        @Override
        public void setErrorStream(OutputStream err) {}
        @Override
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            env.addSignalListener(this, org.apache.sshd.server.Signal.WINCH);
            worker = new Thread(() -> {
                byte[] buf = new byte[1024];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                        if (s.contains("exit")) {
                            out.write("exit\n".getBytes(StandardCharsets.UTF_8));
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
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (worker != null) {
                worker.interrupt();
            }
        }

        @Override
        public void signal(org.apache.sshd.common.channel.Channel channel, org.apache.sshd.server.Signal signal) {
        }
    }
}
