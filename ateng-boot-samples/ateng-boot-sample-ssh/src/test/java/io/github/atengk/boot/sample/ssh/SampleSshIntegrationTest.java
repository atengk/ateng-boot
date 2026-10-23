package io.github.atengk.boot.sample.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ssh.dto.*;
import io.github.atengk.boot.sample.ssh.service.SshScenarioService;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSH 场景样例服务 100% 真实端到端集成测试
 * <p>
 * 全自洽离线验证 Spec 1 的所有核心场景：<br>
 * 1. 同步短命令执行 (exec)<br>
 * 2. 原生 Sudo 提权安全管道 (execSudo)<br>
 * 3. 集群多主机高并发批量巡检 (execBatch)<br>
 * 4. 实时按行流式消费 (execStream)<br>
 * 5. CMDB / 数据库纯内存动态直连描述符 (SshServerInfo)<br>
 * 6. ProxyJump 两级跳板机级联穿透 (Client -> Jump1 -> Jump2 -> Target)<br>
 * 7. 瞬态单次隔离任务 (execOnce)<br>
 * 8. 本地端口转发真实 TCP 流量交互与生命周期释放 (openLocalTunnel)<br>
 * 9. 多行复杂运维脚本管道执行 (execScript)<br>
 * 10. JSR-303 参数格式校验 Fail-Fast 防御
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SSH 场景样例服务端到端集成测试")
class SampleSshIntegrationTest {

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

    @TempDir
    static Path sharedTempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SshScenarioService scenarioService;

    @BeforeAll
    static void startAllEmbeddedServers() throws IOException {
        // 1. 启动轻量级 Echo TCP 服务端（模拟内网数据库/Redis）
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

        // 2. 启动 default 主节点
        defaultServer = createServer("default_key.ser", "testuser", "testpassword", true);
        defaultServer.start();

        // 3. 启动 node1 节点
        node1Server = createServer("node1_key.ser", "worker1", "worker1pass", false);
        node1Server.start();

        // 4. 启动 node2 节点
        node2Server = createServer("node2_key.ser", "worker2", "worker2pass", false);
        node2Server.start();

        // 5. 启动 bastion 节点
        bastionServer = createServer("bastion_key.ser", "ops", "opspassword", true);
        bastionServer.start();

        // 6. 启动两级级联穿透测试节点 (Jump1, Jump2, Target)
        jump1Server = createServer("jump1_key.ser", "jump1_user", "jump1_pass", true);
        jump1Server.start();

        jump2Server = createServer("jump2_key.ser", "jump2_user", "jump2_pass", true);
        jump2Server.start();

        targetServer = createServer("target_key.ser", "target_user", "target_pass", true);
        targetServer.start();
    }

    @AfterAll
    static void stopAllEmbeddedServers() throws IOException {
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
        registry.add("ateng.boot.ssh.port", () -> defaultServer.getPort());
        registry.add("ateng.boot.ssh.servers.node1.port", () -> node1Server.getPort());
        registry.add("ateng.boot.ssh.servers.node2.port", () -> node2Server.getPort());
        registry.add("ateng.boot.ssh.servers.bastion.port", () -> bastionServer.getPort());
    }

    private static SshServer createServer(String keyFile, String expectedUser, String expectedPass, boolean sudoSupport) {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(sharedTempDir.resolve(keyFile)));
        server.setPasswordAuthenticator((u, p, s) -> expectedUser.equals(u) && expectedPass.equals(p));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);

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
            public void start(ChannelSession channel, Environment env) throws IOException {
                worker = new Thread(() -> {
                    try {
                        if (sudoSupport && command.startsWith("sudo -S -p '' ")) {
                            // 读取标准输入中的密码
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
    // 1. Controller Web 契约层端到端测试
    // ==========================================

    @Test
    @DisplayName("测试 Web API 同步短命令执行：POST /api/ssh/exec 成功返回 Result<SshExecResult>")
    void testControllerExecCommand() throws Exception {
        SshExecCommandRequest request = new SshExecCommandRequest();
        request.setCommand("echo CONTROLLER_EXEC_OK");

        mockMvc.perform(post("/api/ssh/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.success", is(true)))
                .andExpect(jsonPath("$.data.exitCode", is(0)))
                .andExpect(jsonPath("$.data.stdout", is("CONTROLLER_EXEC_OK\n")));
    }

    @Test
    @DisplayName("测试 Web API Sudo 提权安全管道：POST /api/ssh/exec-sudo 成功执行提权")
    void testControllerExecSudoCommand() throws Exception {
        SshSudoCommandRequest request = new SshSudoCommandRequest();
        request.setCommand("systemctl restart nginx");
        request.setSudoPassword("testpassword");

        mockMvc.perform(post("/api/ssh/exec-sudo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.success", is(true)))
                .andExpect(jsonPath("$.data.stdout", is("SUDO_SUCCESS: systemctl restart nginx\n")));
    }

    @Test
    @DisplayName("测试 Web API 集群批量并发巡检：POST /api/ssh/exec-batch 并发下发多节点")
    void testControllerExecBatch() throws Exception {
        SshBatchCommandRequest request = new SshBatchCommandRequest();
        request.setServers(List.of("node1", "node2"));
        request.setCommand("echo FLEET_PROBE");

        mockMvc.perform(post("/api/ssh/exec-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.node1.stdout", is("FLEET_PROBE\n")))
                .andExpect(jsonPath("$.data.node2.stdout", is("FLEET_PROBE\n")));
    }

    @Test
    @DisplayName("测试 Web API 流式日志按行消费：POST /api/ssh/stream-log 返回逐行捕获内容")
    void testControllerStreamLog() throws Exception {
        SshExecCommandRequest request = new SshExecCommandRequest();
        request.setCommand("echo STREAMING_LINE");

        mockMvc.perform(post("/api/ssh/stream-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0]", is("STREAMING_LINE")));
    }

    @Test
    @DisplayName("测试 Web API CMDB 动态直连描述符：POST /api/ssh/dynamic-connect 动态解析并执行")
    void testControllerDynamicConnect() throws Exception {
        SshDynamicConnectRequest request = new SshDynamicConnectRequest();
        request.setHost("127.0.0.1");
        request.setPort(node1Server.getPort());
        request.setUsername("worker1");
        request.setPassword("worker1pass");
        request.setCommand("echo DYNAMIC_CMDB_OK");

        mockMvc.perform(post("/api/ssh/dynamic-connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.stdout", is("DYNAMIC_CMDB_OK\n")));
    }

    @Test
    @DisplayName("测试 Web API 本地端口转发隧道：POST /api/ssh/tunnel/open 成功分配随机端口")
    void testControllerOpenTunnel() throws Exception {
        SshTunnelRequest request = new SshTunnelRequest();
        request.setLocalPort(0);
        request.setRemoteHost("127.0.0.1");
        request.setRemotePort(echoTcpServer.getLocalPort());

        mockMvc.perform(post("/api/ssh/tunnel/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data").isNumber());
    }

    @Test
    @DisplayName("测试 JSR-303 参数校验 Fail-Fast：空命令立即拦截返回校验失败响应")
    void testControllerValidationFailFast() throws Exception {
        SshExecCommandRequest request = new SshExecCommandRequest();
        request.setCommand(""); // 空命令

        mockMvc.perform(post("/api/ssh/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("命令内容不能为空")));
    }

    // ==========================================
    // 2. ScenarioService 核心底座场景测试
    // ==========================================

    @Test
    @DisplayName("测试两级 ProxyJump 级联穿透：Client -> Jump1 -> Jump2 -> Target")
    void testScenarioCascadingProxyJump() {
        SshExecResult result = scenarioService.runCascadingProxyJump(
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
        SshServerInfo server = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(node1Server.getPort())
                .username("worker1")
                .password("worker1pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshExecResult result = scenarioService.runTransientTask(server, "echo TRANSIENT_RUN");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).isEqualTo("TRANSIENT_RUN\n");
    }

    @Test
    @DisplayName("测试本地端口转发双向 TCP 流量传输：本地客户端向远端真实 Echo TCP 发起通信")
    void testScenarioPortForwardingTcpFlow() throws Exception {
        String reply = scenarioService.forwardAndQueryTcp(
                "127.0.0.1",
                echoTcpServer.getLocalPort(),
                "PROBE_MSG"
        );

        assertThat(reply).isEqualTo("ECHO_REPLY:PROBE_MSG");
    }

    @Test
    @DisplayName("测试多行复杂运维脚本管道执行：execScript 成功执行")
    void testScenarioMaintenanceScript() {
        String script = """
                echo step1
                echo step2
                echo step3
                """;

        SshExecResult result = scenarioService.runMaintenanceScript(script);
        assertThat(result.isSuccess()).isTrue();
    }
}
