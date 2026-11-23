package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshTemplate 物理连通性探针与异步命令执行端到端单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("SshTemplate 物理连通性探针与异步命令执行单元测试")
class SshTemplateProbeAndAsyncTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry registry;
    private SshTemplate template;
    private ThreadPoolTaskExecutor taskExecutor;
    private int serverPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // 1. 启动嵌入式 SshServer
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password));

        sshServer.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(ChannelSession channel, String command) {
                return new Command() {
                    private OutputStream out;
                    private ExitCallback callback;

                    @Override
                    public void setInputStream(InputStream in) {
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
                        if (command.startsWith("echo ")) {
                            String msg = command.substring(5);
                            out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                        } else {
                            out.write(("OUTPUT:" + command + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                        }
                    }

                    @Override
                    public void destroy(ChannelSession channel) {
                    }
                };
            }
        });
        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 初始化 SshClient 与底座设施
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(serverPort);
        properties.setUsername("testuser");
        properties.setPassword("testpass");
        properties.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        registry = new SshClientRegistry();
        registry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(properties));

        SshServerInfo backupNode = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("testpass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();
        registry.register("backup", backupNode);

        sessionPool = new SshSessionPool(sshClient, properties.getPool());

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setThreadNamePrefix("test-ssh-async-");
        taskExecutor.initialize();

        template = new SshTemplate(sessionPool, registry, properties, taskExecutor);
    }

    @AfterEach
    void tearDown() throws IOException {
        SshContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();

        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        if (sshServer != null) {
            sshServer.stop();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    // ==========================================
    // 1. 物理连通性探针测试
    // ==========================================

    @Test
    @DisplayName("测试缺省节点连通性探针成功")
    void testConnectionDefaultSuccess() {
        boolean healthy = template.testConnection();
        assertThat(healthy).isTrue();
    }

    @Test
    @DisplayName("测试具名节点连通性探针成功")
    void testConnectionNamedSuccess() {
        boolean healthy = template.testConnection("backup");
        assertThat(healthy).isTrue();
    }

    @Test
    @DisplayName("测试显式描述符与自定义超时连通性探针成功")
    void testConnectionExplicitWithTimeoutSuccess() {
        SshServerInfo node = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("testpass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        boolean healthy = template.testConnection(node, Duration.ofSeconds(3));
        assertThat(healthy).isTrue();
    }

    @Test
    @DisplayName("测试不可达节点连通性探针返回 false 不抛异常")
    void testConnectionUnreachableReturnsFalse() {
        SshServerInfo badNode = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(9) // Discard 端口，不可达
                .username("testuser")
                .password("testpass")
                .connectTimeout(Duration.ofMillis(500))
                .authTimeout(Duration.ofMillis(500))
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        boolean healthy = template.testConnection(badNode, Duration.ofMillis(500));
        assertThat(healthy).isFalse();
    }

    @Test
    @DisplayName("测试错误凭据节点连通性探针返回 false")
    void testConnectionWrongPasswordReturnsFalse() {
        SshServerInfo badAuthNode = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("wrongpassword")
                .connectTimeout(Duration.ofSeconds(2))
                .authTimeout(Duration.ofSeconds(2))
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        boolean healthy = template.testConnection(badAuthNode);
        assertThat(healthy).isFalse();
    }

    @Test
    @DisplayName("测试 testConnectionOrThrow 在有效节点无异常抛出")
    void testConnectionOrThrowSuccess() {
        template.testConnectionOrThrow();
        template.testConnectionOrThrow("backup");
    }

    @Test
    @DisplayName("测试 testConnectionOrThrow 在错误凭据节点抛出 PROBE_FAILED 异常")
    void testConnectionOrThrowFailure() {
        SshServerInfo badAuthNode = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("wrongpassword")
                .connectTimeout(Duration.ofSeconds(2))
                .authTimeout(Duration.ofSeconds(2))
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        assertThatThrownBy(() -> template.testConnectionOrThrow(badAuthNode))
                .isInstanceOf(SshOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", SshResultCode.PROBE_FAILED)
                .hasMessageContaining("SSH 节点连通性探测失败");
    }

    // ==========================================
    // 2. 异步命令执行测试
    // ==========================================

    @Test
    @DisplayName("测试 execAsync 缺省节点异步执行命令")
    void testExecAsyncDefault() throws Exception {
        CompletableFuture<SshExecResult> future = template.execAsync("echo hello-async");
        SshExecResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("hello-async");
    }

    @Test
    @DisplayName("测试 execAsync 具名节点与扩展参数异步执行")
    void testExecAsyncNamedWithOptions() throws Exception {
        SshExecOptions options = SshExecOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<SshExecResult> future = template.execAsync("backup", "echo named-async", options);
        SshExecResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("named-async");
    }

    @Test
    @DisplayName("测试 execScriptAsync 多行脚本异步执行")
    void testExecScriptAsync() throws Exception {
        String script = "echo line1\r\necho line2";
        CompletableFuture<SshExecResult> future = template.execScriptAsync("backup", script);
        SshExecResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).contains("line1", "line2");
    }

    @Test
    @DisplayName("测试 execAsync 四维上下文自动透传")
    void testExecAsyncContextPropagation() throws Exception {
        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUserId = new AtomicReference<>();

        SshSessionPool customPool = new SshSessionPool(sshClient, new SshProperties().getPool()) {
            @Override
            public <T> T execute(SshServerInfo serverInfo, io.github.atengk.boot.ssh.pool.SshSessionCallback<T> callback) {
                asyncTraceId.set(MDC.get("traceId"));
                asyncUserId.set(UserContextHolder.getUserId());
                return super.execute(serverInfo, callback);
            }
        };

        SshProperties props = new SshProperties();
        props.setHost("127.0.0.1");
        props.setPort(serverPort);
        props.setUsername("testuser");
        props.setPassword("testpass");
        props.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        SshTemplate customTemplate = new SshTemplate(customPool, registry, props, taskExecutor);

        MDC.put("traceId", "trace-ssh-async-12345");
        UserContextHolder.setUserId("9999");

        try {
            CompletableFuture<SshExecResult> future = customTemplate.execAsync("echo test-context");
            SshExecResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.isSuccess()).isTrue();
            assertThat(asyncTraceId.get()).isEqualTo("trace-ssh-async-12345");
            assertThat(asyncUserId.get()).isEqualTo("9999");
        } finally {
            customPool.close();
        }
    }
}
