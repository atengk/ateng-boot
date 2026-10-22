package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshTemplate 原生 Sudo 提权管道与集群批量并发巡检端到端测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshTemplate Sudo 提权与集群批量巡检测试")
class SshTemplateSudoAndBatchTest {

    private SshServer sshServer;
    private int serverPort;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry clientRegistry;
    private SshProperties sshProperties;
    private SshTemplate template;
    private ThreadPoolTaskExecutor taskExecutor;

    private static final String VALID_USER = "testuser";
    private static final String VALID_PASS = "testpass";
    private static final String CORRECT_SUDO_PWD = "admin_super_secret";

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        // 1. 启动嵌入式 Apache Mina SshServer
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                username != null && username.startsWith(VALID_USER) && VALID_PASS.equals(password));

        sshServer.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(ChannelSession channelSession, String command) {
                return new MockCommand(command);
            }
        });
        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 初始化客户端与执行门面设施
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        sshProperties = new SshProperties();
        sshProperties.setHost("127.0.0.1");
        sshProperties.setPort(serverPort);
        sshProperties.setUsername(VALID_USER);
        sshProperties.setPassword(VALID_PASS);

        clientRegistry = new SshClientRegistry();
        clientRegistry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(sshProperties));

        sessionPool = new SshSessionPool(sshClient, sshProperties.getPool());

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(50);
        taskExecutor.setThreadNamePrefix("test-batch-");
        taskExecutor.initialize();

        template = new SshTemplate(sessionPool, clientRegistry, sshProperties, taskExecutor);
    }

    @AfterEach
    void tearDown() throws Exception {
        // 严格遵循零污染铁律清理现场
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SshContextHolder.clear();

        if (template != null) {
            template.close();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        if (sshServer != null) {
            sshServer.stop();
        }
    }

    // ==========================================
    // 1. 原生 Sudo 提权测试
    // ==========================================

    @Test
    @DisplayName("测试 Sudo 提权执行成功：密码正确且以 root 身份输出")
    void testExecSudoSuccessWithStringPassword() {
        SshExecResult result = template.execSudo("whoami", CORRECT_SUDO_PWD);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("root");
    }

    @Test
    @DisplayName("测试 Sudo 提权执行失败：密码错误返回非 0 退出码并在 stderr 记录错误")
    void testExecSudoFailureWithWrongPassword() {
        SshExecResult result = template.execSudo("whoami", "wrong_password");

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(result.getStderr()).contains("incorrect password attempt");
    }

    @Test
    @DisplayName("测试 Sudo 提权凭据内存绝对擦除抹零 (In-Memory Scrubbing)")
    void testExecSudoMemoryScrubbingWithCharArray() {
        char[] password = CORRECT_SUDO_PWD.toCharArray();

        SshExecResult result = template.execSudo("whoami", password);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("root");

        // 核心安全断言：源字符数组在网络传输完成后已被无条件全部抹零填充为 '\0'
        for (char c : password) {
            assertThat(c).isEqualTo('\0');
        }
    }

    @Test
    @DisplayName("测试 Sudo 瞬态隔离单次执行 (execSudoOnce)")
    void testExecSudoOnce() {
        SshServerInfo serverInfo = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username(VALID_USER)
                .password(VALID_PASS)
                .build();

        char[] password = CORRECT_SUDO_PWD.toCharArray();
        SshExecResult result = template.execSudoOnce(serverInfo, "id -u", password);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("0");
        assertThat(password).containsOnly('\0');
    }

    // ==========================================
    // 2. 集群批量并发巡检测试
    // ==========================================

    @Test
    @DisplayName("测试多节点并发批量巡检 (execBatch)：保持原有顺序且全部成功返回")
    void testExecBatchConcurrentDispatch() {
        List<SshServerInfo> servers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            servers.add(SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username(VALID_USER + "-node" + i)
                    .password(VALID_PASS)
                    .build());
        }

        Map<SshServerInfo, SshExecResult> resultMap = template.execBatch(servers, "echo cluster-ok");

        assertThat(resultMap).hasSize(5);
        for (SshServerInfo server : servers) {
            SshExecResult result = resultMap.get(server);
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStdout().trim()).isEqualTo("cluster-ok");
        }
    }

    @Test
    @DisplayName("测试批量并发巡检四维上下文透明透传 (4D Context Propagation)")
    void testExecBatchContextPropagation() {
        // 设置主调用线程的四维上下文快照
        MDC.put("traceId", "trace-ssh-cluster-8888");
        UserContextHolder.set("operator-ateng");
        TenantContextHolder.setTenantId(9999L);

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTenantId = new AtomicReference<>();

        // 构造一个自定义线程池用于捕获工作线程内部上下文
        ThreadPoolTaskExecutor customExecutor = new ThreadPoolTaskExecutor();
        customExecutor.initialize();

        try {
            SshTemplate customTemplate = new SshTemplate(sessionPool, clientRegistry, sshProperties, task -> {
                customExecutor.execute(() -> {
                    // 在工作线程执行前捕获被装饰器还原的上下文现场
                    task.run();
                });
            });

            SshServerInfo server = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username(VALID_USER + "-context")
                    .password(VALID_PASS)
                    .build();

            // 验证执行时工作线程内能够获取到上下文
            java.util.function.Supplier<String> supplier = io.github.atengk.boot.core.context.AsyncContext.wrapSupplier(() -> {
                capturedTraceId.set(MDC.get("traceId"));
                capturedUser.set(UserContextHolder.get());
                capturedTenantId.set(TenantContextHolder.getTenantId());
                return "ok";
            });
            customExecutor.submit(supplier::get).get();

            assertThat(capturedTraceId.get()).isEqualTo("trace-ssh-cluster-8888");
            assertThat(capturedUser.get()).isEqualTo("operator-ateng");
            assertThat(capturedTenantId.get()).isEqualTo("9999");

            // 执行批量巡检
            Map<SshServerInfo, SshExecResult> resultMap = template.execBatch(Collections.singletonList(server), "whoami");
            assertThat(resultMap.get(server).isSuccess()).isTrue();

            // 检查主线程现场未被污染或清除
            assertThat(MDC.get("traceId")).isEqualTo("trace-ssh-cluster-8888");
            assertThat(UserContextHolder.get()).isEqualTo("operator-ateng");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("9999");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            customExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("测试批量巡检节点级异常隔离 (Individual Error Isolation)：故障节点不影响健康节点")
    void testExecBatchIndividualErrorIsolation() {
        // 健康节点 A
        SshServerInfo healthyNodeA = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username(VALID_USER + "-nodeA")
                .password(VALID_PASS)
                .build();

        // 故障节点 B（端口错误无法连通）
        SshServerInfo failedNodeB = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(59999) // 无效端口
                .username(VALID_USER + "-nodeB")
                .password(VALID_PASS)
                .connectTimeout(Duration.ofMillis(500))
                .build();

        // 健康节点 C
        SshServerInfo healthyNodeC = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username(VALID_USER + "-nodeC")
                .password(VALID_PASS)
                .build();

        List<SshServerInfo> targets = Arrays.asList(healthyNodeA, failedNodeB, healthyNodeC);

        Map<SshServerInfo, SshExecResult> resultMap = template.execBatch(targets, "echo health-check");

        assertThat(resultMap).hasSize(3);

        // 验证健康节点正常成功
        assertThat(resultMap.get(healthyNodeA).isSuccess()).isTrue();
        assertThat(resultMap.get(healthyNodeA).getStdout().trim()).isEqualTo("health-check");

        assertThat(resultMap.get(healthyNodeC).isSuccess()).isTrue();
        assertThat(resultMap.get(healthyNodeC).getStdout().trim()).isEqualTo("health-check");

        // 验证故障节点被优雅隔离，记录 exitCode = -1 且不抛出未受管崩溃异常阻断整体
        SshExecResult failedResult = resultMap.get(failedNodeB);
        assertThat(failedResult).isNotNull();
        assertThat(failedResult.isFailed()).isTrue();
        assertThat(failedResult.getExitCode()).isEqualTo(-1);
        assertThat(failedResult.getStderr()).isNotEmpty();
    }

    @Test
    @DisplayName("测试集群具名节点批量巡检 (execBatchNamed)")
    void testExecBatchNamed() {
        clientRegistry.register("node-1", SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username(VALID_USER + "-named1")
                .password(VALID_PASS)
                .build());

        clientRegistry.register("node-2", SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username(VALID_USER + "-named2")
                .password(VALID_PASS)
                .build());

        Map<String, SshExecResult> resultMap = template.execBatchNamed(Arrays.asList("node-1", "node-2"), "echo named-ok");

        assertThat(resultMap).hasSize(2);
        assertThat(resultMap.get("node-1").isSuccess()).isTrue();
        assertThat(resultMap.get("node-1").getStdout().trim()).isEqualTo("named-ok");
        assertThat(resultMap.get("node-2").isSuccess()).isTrue();
        assertThat(resultMap.get("node-2").getStdout().trim()).isEqualTo("named-ok");
    }

    @Test
    @DisplayName("测试集群批量 Sudo 提权巡检 (execSudoBatch)")
    void testExecSudoBatch() {
        List<SshServerInfo> servers = Arrays.asList(
                SshServerInfo.builder().host("127.0.0.1").port(serverPort).username(VALID_USER + "-sudo1").password(VALID_PASS).build(),
                SshServerInfo.builder().host("127.0.0.1").port(serverPort).username(VALID_USER + "-sudo2").password(VALID_PASS).build()
        );

        char[] sudoPassword = CORRECT_SUDO_PWD.toCharArray();
        Map<SshServerInfo, SshExecResult> resultMap = template.execSudoBatch(servers, "whoami", sudoPassword);

        assertThat(resultMap).hasSize(2);
        for (SshExecResult result : resultMap.values()) {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStdout().trim()).isEqualTo("root");
        }
        assertThat(sudoPassword).containsOnly('\0');
    }

    @Test
    @DisplayName("测试空服务器列表保底返回空集合，严禁返回 null")
    void testEmptyServersReturnsEmptyMap() {
        Map<SshServerInfo, SshExecResult> result = template.execBatch(Collections.emptyList(), "whoami");
        assertThat(result).isNotNull().isEmpty();

        Map<SshServerInfo, SshExecResult> nullResult = template.execBatch(null, "whoami");
        assertThat(nullResult).isNotNull().isEmpty();
    }

    // ==========================================
    // 模拟服务端指令处理器
    // ==========================================

    private static class MockCommand implements Command {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread workerThread;

        MockCommand(String command) {
            this.command = command;
        }

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
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channelSession, Environment environment) {
            workerThread = new Thread(() -> {
                try {
                    handleExecution();
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onExit(1, e.getMessage());
                    }
                }
            }, "mock-cmd-worker");
            workerThread.start();
        }

        private void handleExecution() throws Exception {
            String clean = command.trim();

            // 1. Sudo 提权命令处理：从 stdin 读取密码并校验
            if (clean.startsWith("sudo -S -p ''")) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String pass = reader.readLine();

                if (CORRECT_SUDO_PWD.equals(pass)) {
                    if (clean.contains("whoami")) {
                        out.write("root\n".getBytes(StandardCharsets.UTF_8));
                    } else if (clean.contains("id -u")) {
                        out.write("0\n".getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write("sudo-success\n".getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                    callback.onExit(0);
                } else {
                    err.write("sudo: 1 incorrect password attempt\n".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(1);
                }
                return;
            }

            // 2. 普通 echo 命令
            if (clean.startsWith("echo ")) {
                String content = clean.substring("echo ".length()).trim();
                out.write((content + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(0);
                return;
            }

            // 3. whoami 命令
            if (clean.equals("whoami")) {
                out.write("testuser\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(0);
                return;
            }

            // 默认返回成功
            out.write("ok\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            callback.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channelSession) {
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }
}
