package io.github.atengk.boot.sample.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ssh.dto.SftpAppendTextRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpCopyMoveRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpDeleteDirectoryRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpDirectorySyncRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpLargeTransferRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpPathRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpRenameRequest;
import io.github.atengk.boot.sample.ssh.dto.SftpWriteTextRequest;
import io.github.atengk.boot.sample.ssh.dto.SshBatchInspectRequest;
import io.github.atengk.boot.sample.ssh.dto.SshCommandRequest;
import io.github.atengk.boot.sample.ssh.dto.SshContextSwitchRequest;
import io.github.atengk.boot.sample.ssh.dto.SshDynamicHostRequest;
import io.github.atengk.boot.sample.ssh.dto.SshProxyJumpRequest;
import io.github.atengk.boot.sample.ssh.dto.SshScriptRequest;
import io.github.atengk.boot.sample.ssh.dto.SshStreamLogRequest;
import io.github.atengk.boot.sample.ssh.dto.SshSudoRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTcpEchoRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTunnelCloseRequest;
import io.github.atengk.boot.sample.ssh.dto.SshTunnelOpenRequest;
import io.github.atengk.boot.ssh.context.SshContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSH 样例工程 5 大领域控制器 Web 端点双模全真回归集成测试
 * <p>
 * 验证在真实 Spring Boot Web 容器环境下：
 * 1. SshCommandController: 同步短命令、非阻塞异步 Future、Sudo 提权管道（密码抹零）、多行脚本、流式日志按行捕获、连通性探针
 * 2. SshClusterController: 集群受控批量巡检、CMDB 纯内存资产动态直连、线程上下文动态切源
 * 3. SshTunnelController: 本地端口转发建立与对称释放、ProxyJump 两级跳板机级联穿透、真实 TCP 流量透传回显
 * 4. SftpDocumentController: 表单文件原子上传、流式文件下载、纯文本配置读写、增量追加写、跨目录移动、原子改名、元数据查询、存在性探查、单文件删除
 * 5. SftpSyncController: 整包目录树双向镜像同步、DFS 递归删除非空目录、大文件节流进度上传
 * 6. SshTerminalController: WebSSH 终端规格与诊断指标查询
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@SpringBootTest
@AutoConfigureMockMvc
@Slf4j
@DisplayName("SSH 样例工程 5 大领域控制器 Web 端点双模全真回归测试")
public class SampleSshWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private static boolean isLocalProfile() {
        String profile = System.getProperty("spring.profiles.active");
        if (profile != null && profile.contains("local")) {
            return true;
        }
        String envProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        return envProfile != null && envProfile.contains("local");
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

    private static void startAllEmbeddedServers() throws IOException {
        if (sharedTempDir == null) {
            sharedTempDir = Files.createTempDirectory("sample-ssh-web-embedded-");
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

        // 5. 启动跳板机穿透测试节点 (Jump1, Jump2, Target)
        jump1Server = createServer("jump1_key.ser", "jump1_user", "jump1_pass", true);
        jump1Server.start();
        jump2Server = createServer("jump2_key.ser", "jump2_user", "jump2_pass", true);
        jump2Server.start();
        targetServer = createServer("target_key.ser", "target_user", "target_pass", true);
        targetServer.start();
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
        }

        server.setCommandFactory((channel, command) -> new Command() {
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
                }, "sample-ssh-web-cmd-worker");
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
        if (sharedTempDir != null && Files.exists(sharedTempDir)) {
            org.springframework.util.FileSystemUtils.deleteRecursively(sharedTempDir);
        }
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

    @AfterEach
    void tearDown() {
        SshContextHolder.clear();
    }

    // ==========================================
    // 1. SshCommandController 端点测试
    // ==========================================

    @Test
    @DisplayName("SshCommandController: 同步短命令执行成功与参数校验拦截")
    void testCommandExec() throws Exception {
        // 1. 成功执行默认主节点命令
        SshCommandRequest request = SshCommandRequest.builder()
                .command("echo HELLO_CONTROLLER")
                .build();

        mockMvc.perform(post("/sample/ssh/command/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.exitCode", is(0)))
                .andExpect(jsonPath("$.data.stdout", containsString("HELLO_CONTROLLER")))
                .andExpect(jsonPath("$.data.success", is(true)));

        // 2. 指定命名节点执行
        SshCommandRequest nodeRequest = SshCommandRequest.builder()
                .serverName("node1")
                .command("echo HELLO_NODE1")
                .build();

        mockMvc.perform(post("/sample/ssh/command/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nodeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stdout", containsString("HELLO_NODE1")));

        // 3. JSR-303 参数校验拦截 Fail-Fast (空白命令)
        SshCommandRequest invalidRequest = SshCommandRequest.builder().command("").build();
        mockMvc.perform(post("/sample/ssh/command/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", notNullValue()))
                .andExpect(jsonPath("$.msg", containsString("命令内容不能为空")));
    }

    @Test
    @DisplayName("SshCommandController: 非阻塞异步命令下发执行成功")
    void testCommandExecAsync() throws Exception {
        SshCommandRequest request = SshCommandRequest.builder()
                .command("echo ASYNC_WEB_OK")
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/ssh/command/exec-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stdout", containsString("ASYNC_WEB_OK")));
    }

    @Test
    @DisplayName("SshCommandController: 原生 Sudo 提权管道执行与参数校验")
    void testCommandExecSudo() throws Exception {
        // 1. 成功提权
        SshSudoRequest request = SshSudoRequest.builder()
                .command("systemctl restart nginx")
                .sudoPassword("testpassword")
                .build();

        mockMvc.perform(post("/sample/ssh/command/exec-sudo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.exitCode", is(0)))
                .andExpect(jsonPath("$.data.stdout", containsString("SUDO_SUCCESS: systemctl restart nginx")));

        // 2. 密码空白 Fail-Fast 拦截
        SshSudoRequest invalidRequest = SshSudoRequest.builder()
                .command("systemctl restart nginx")
                .sudoPassword("")
                .build();

        mockMvc.perform(post("/sample/ssh/command/exec-sudo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg", containsString("Sudo 密码不能为空")));
    }

    @Test
    @DisplayName("SshCommandController: 多行脚本管道执行成功")
    void testCommandExecScript() throws Exception {
        SshScriptRequest request = SshScriptRequest.builder()
                .scriptBody("echo SCRIPT_LINE1\necho SCRIPT_LINE2")
                .build();

        mockMvc.perform(post("/sample/ssh/command/exec-script")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.exitCode", is(0)));
    }

    @Test
    @DisplayName("SshCommandController: 长命令流式按行消费成功")
    void testCommandStreamLog() throws Exception {
        SshStreamLogRequest request = SshStreamLogRequest.builder()
                .command("echo STREAM_LINE_A")
                .build();

        mockMvc.perform(post("/sample/ssh/command/stream-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]", containsString("STREAM_LINE_A")));
    }

    @Test
    @DisplayName("SshCommandController: 连通性探针探测主节点与命名节点")
    void testCommandProbeHealth() throws Exception {
        mockMvc.perform(get("/sample/ssh/command/probe-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is(true)));

        mockMvc.perform(get("/sample/ssh/command/probe-health")
                        .param("serverName", "node1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));
    }

    // ==========================================
    // 2. SshClusterController 端点测试
    // ==========================================

    @Test
    @DisplayName("SshClusterController: 集群高级批量巡检聚合回执与熔断参数校验")
    void testClusterBatchInspect() throws Exception {
        // 1. 成功巡检 node1 与 node2
        SshBatchInspectRequest request = SshBatchInspectRequest.builder()
                .serverNames(List.of("node1", "node2"))
                .command("echo BATCH_PING")
                .concurrency(2)
                .failFast(false)
                .build();

        mockMvc.perform(post("/sample/ssh/cluster/batch-inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount", is(2)))
                .andExpect(jsonPath("$.data.successCount", is(2)))
                .andExpect(jsonPath("$.data.allSuccess", is(true)))
                .andExpect(jsonPath("$.data.details['node1'].stdout", containsString("BATCH_PING")));

        // 2. 空节点集合拦截 Fail-Fast
        SshBatchInspectRequest invalidRequest = SshBatchInspectRequest.builder()
                .serverNames(Collections.emptyList())
                .command("uptime")
                .build();

        mockMvc.perform(post("/sample/ssh/cluster/batch-inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg", containsString("巡检节点集合不能为空")));
    }

    @Test
    @DisplayName("SshClusterController: CMDB 纯内存资产动态直连与端口校验")
    void testClusterDynamicConnect() throws Exception {
        SshDynamicHostRequest request = SshDynamicHostRequest.builder()
                .host("127.0.0.1")
                .port(node1Server.getPort())
                .username("worker1")
                .password("worker1pass")
                .command("echo DYNAMIC_HOST_SUCCESS")
                .build();

        mockMvc.perform(post("/sample/ssh/cluster/dynamic-connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stdout", containsString("DYNAMIC_HOST_SUCCESS")));

        // 端口越界校验
        SshDynamicHostRequest invalidRequest = SshDynamicHostRequest.builder()
                .host("127.0.0.1")
                .port(70000)
                .username("worker1")
                .command("uptime")
                .build();

        mockMvc.perform(post("/sample/ssh/cluster/dynamic-connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg", containsString("端口范围必须为 1~65535")));
    }

    @Test
    @DisplayName("SshClusterController: 线程上下文动态切源执行")
    void testClusterContextSwitch() throws Exception {
        SshContextSwitchRequest request = SshContextSwitchRequest.builder()
                .serverName("node1")
                .command("echo CTX_SWITCH_OK")
                .build();

        mockMvc.perform(post("/sample/ssh/cluster/context-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is("CTX_SWITCH_OK")));
    }

    // ==========================================
    // 3. SshTunnelController 端点测试
    // ==========================================

    @Test
    @DisplayName("SshTunnelController: 本地端口转发建立与对称释放")
    void testTunnelLifecycle() throws Exception {
        // 1. 建立受管本地端口转发（绑定到 echoTcpServer）
        SshTunnelOpenRequest openReq = SshTunnelOpenRequest.builder()
                .localPort(0)
                .remoteHost("127.0.0.1")
                .remotePort(echoTcpServer.getLocalPort())
                .build();

        MvcResult openResult = mockMvc.perform(post("/sample/ssh/tunnel/open-tunnel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(openReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.alive", is(true)))
                .andReturn();

        String responseBody = openResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int localPort = objectMapper.readTree(responseBody).path("data").path("localPort").asInt();
        assertThat(localPort).isGreaterThan(0);

        // 2. 对称安全释放受管隧道
        SshTunnelCloseRequest closeReq = SshTunnelCloseRequest.builder()
                .localPort(localPort)
                .build();

        mockMvc.perform(post("/sample/ssh/tunnel/close-tunnel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is(true)));
    }

    @Test
    @DisplayName("SshTunnelController: ProxyJump 两级跳板机级联穿透执行")
    void testProxyJump() throws Exception {
        SshProxyJumpRequest request = SshProxyJumpRequest.builder()
                .targetHost("127.0.0.1")
                .targetPort(targetServer.getPort())
                .jump1Host("127.0.0.1")
                .jump1Port(jump1Server.getPort())
                .jump2Host("127.0.0.1")
                .jump2Port(jump2Server.getPort())
                .command("echo CASCADE_JUMP_SUCCESS")
                .build();

        mockMvc.perform(post("/sample/ssh/tunnel/proxy-jump")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.exitCode", is(0)))
                .andExpect(jsonPath("$.data.stdout", containsString("CASCADE_JUMP_SUCCESS")));
    }

    @Test
    @DisplayName("SshTunnelController: 端口转发真实 TCP 流量交互透传")
    void testTunnelTcpEcho() throws Exception {
        SshTcpEchoRequest request = SshTcpEchoRequest.builder()
                .remoteHost("127.0.0.1")
                .remotePort(echoTcpServer.getLocalPort())
                .message("HELLO_TUNNEL_TCP")
                .build();

        mockMvc.perform(post("/sample/ssh/tunnel/tcp-echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is("ECHO_REPLY:HELLO_TUNNEL_TCP")));
    }

    // ==========================================
    // 4. SftpDocumentController 端点测试
    // ==========================================

    @Test
    @DisplayName("SftpDocumentController: 表单文件原子上传、存在性校验、元数据获取、流式下载与单文件删除")
    void testSftpDocumentLifecycle() throws Exception {
        String remotePath = "/upload/test-notice.txt";
        byte[] fileBytes = "ENTERPRISE_NOTICE_CONTENT".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notice.txt", MediaType.TEXT_PLAIN_VALUE, fileBytes);

        // 1. 表单上传
        mockMvc.perform(multipart("/sample/ssh/sftp-doc/upload")
                        .file(file)
                        .param("remotePath", remotePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("上传成功")));

        // 2. 检查存在性
        mockMvc.perform(get("/sample/ssh/sftp-doc/exists")
                        .param("remotePath", remotePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is(true)));

        // 3. 查询元数据
        mockMvc.perform(get("/sample/ssh/sftp-doc/info")
                        .param("remotePath", remotePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.filename", is("test-notice.txt")))
                .andExpect(jsonPath("$.data.size").value(String.valueOf(fileBytes.length)));

        // 4. 流式下载
        mockMvc.perform(get("/sample/ssh/sftp-doc/download")
                        .param("remotePath", remotePath))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("test-notice.txt")));

        // 5. 单文件确定性删除
        SftpPathRequest deleteReq = SftpPathRequest.builder().remotePath(remotePath).build();
        mockMvc.perform(post("/sample/ssh/sftp-doc/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is(true)));
    }

    @Test
    @DisplayName("SftpDocumentController: 文本配置写入、读取、增量追加、改名与移动")
    void testSftpTextOperationsAndRenameMove() throws Exception {
        String path1 = "/conf/app.properties";
        String path2 = "/conf/app-renamed.properties";
        String path3 = "/archive/app-renamed.properties";

        // 1. 写入文本配置
        SftpWriteTextRequest writeReq = SftpWriteTextRequest.builder()
                .remotePath(path1)
                .content("KEY_A=VALUE_A\n")
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-doc/write-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(writeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 2. 读取文本配置
        mockMvc.perform(get("/sample/ssh/sftp-doc/read-text")
                        .param("remotePath", path1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", containsString("KEY_A=VALUE_A")));

        // 3. 增量追加写
        SftpAppendTextRequest appendReq = SftpAppendTextRequest.builder()
                .remotePath(path1)
                .content("KEY_B=VALUE_B\n")
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-doc/append-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(appendReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        mockMvc.perform(get("/sample/ssh/sftp-doc/read-text")
                        .param("remotePath", path1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", containsString("KEY_B=VALUE_B")));

        // 4. 重命名
        SftpRenameRequest renameReq = SftpRenameRequest.builder()
                .oldPath(path1)
                .newPath(path2)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-doc/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        // 5. 跨目录移动
        SftpCopyMoveRequest moveReq = SftpCopyMoveRequest.builder()
                .sourcePath(path2)
                .targetPath(path3)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-doc/copy-move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));

        mockMvc.perform(get("/sample/ssh/sftp-doc/exists")
                        .param("remotePath", path3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is(true)));
    }

    // ==========================================
    // 5. SftpSyncController 端点测试
    // ==========================================

    @Test
    @DisplayName("SftpSyncController: 目录树整包双向镜像上传、下载与 DFS 递归删除")
    void testSftpSyncLifecycle() throws Exception {
        Path localSourceDir = testTempDir.resolve("local-source");
        Files.createDirectories(localSourceDir.resolve("sub-folder"));
        Files.writeString(localSourceDir.resolve("sub-folder/file1.txt"), "SYNC_PAYLOAD_1");

        Path localDownloadDir = testTempDir.resolve("local-download");
        Files.createDirectories(localDownloadDir);

        String remoteSyncDir = "/sync-test/package-a";

        // 1. 目录树整包上传
        SftpDirectorySyncRequest uploadReq = SftpDirectorySyncRequest.builder()
                .localDir(localSourceDir.toAbsolutePath().toString())
                .remoteDir(remoteSyncDir)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-sync/upload-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success", is(true)));

        // 2. 目录树整包下载
        SftpDirectorySyncRequest downloadReq = SftpDirectorySyncRequest.builder()
                .localDir(localDownloadDir.toAbsolutePath().toString())
                .remoteDir(remoteSyncDir)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-sync/download-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(downloadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success", is(true)));

        assertThat(Files.exists(localDownloadDir.resolve("sub-folder/file1.txt"))).isTrue();

        // 3. DFS 深度优先递归清空删除非空目录
        SftpDeleteDirectoryRequest deleteReq = SftpDeleteDirectoryRequest.builder()
                .remotePath(remoteSyncDir)
                .recursive(true)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-sync/delete-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", is(true)));
    }

    @Test
    @DisplayName("SftpSyncController: 模拟大文件节流防抖进度上传")
    void testSftpUploadLargeThrottled() throws Exception {
        SftpLargeTransferRequest request = SftpLargeTransferRequest.builder()
                .remotePath("/transfer/large-test.bin")
                .sizeKb(128)
                .build();

        mockMvc.perform(post("/sample/ssh/sftp-sync/upload-large")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("大文件节流上传成功")));
    }

    // ==========================================
    // 6. SshTerminalController 端点测试
    // ==========================================

    @Test
    @DisplayName("SshTerminalController: WebSSH 终端规格指标诊断端点")
    void testTerminalInfo() throws Exception {
        mockMvc.perform(get("/sample/ssh/terminal/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.defaultTerm", is("xterm-256color")))
                .andExpect(jsonPath("$.data.defaultCols", is(80)))
                .andExpect(jsonPath("$.data.defaultRows", is(24)))
                .andExpect(jsonPath("$.data.websocketEndpoint", is("/ws/ssh")))
                .andExpect(jsonPath("$.data.status", is("UP")));
    }
}
