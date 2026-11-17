package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.interceptor.HighRiskCommandInterceptor;
import io.github.atengk.boot.ssh.interceptor.SshCommandInterceptor;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshPoolStats;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
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

import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshTemplate 远程命令执行与孤儿进程信号防卫端到端单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshTemplate 远程命令执行引擎与信号防卫单元测试")
class SshTemplateCommandExecutionTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry registry;
    private SshTemplate template;
    private int serverPort;

    private final AtomicReference<String> receivedSignal = new AtomicReference<>();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        receivedSignal.set(null);

        // 1. 启动嵌入式 SshServer，定制 CommandFactory 模拟 Linux/Unix Shell 执行
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password));

        sshServer.setCommandFactory(new TestCommandFactory());
        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 初始化客户端与底座设施
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

        sessionPool = new SshSessionPool(sshClient, properties.getPool());
        template = new SshTemplate(sessionPool, registry, properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        SshContextHolder.clear();
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试同步命令执行：成功捕获 exitCode=0 与标准输出内容")
    void testSynchronousCommandExecution() {
        SshExecResult result = template.exec("echo hello-world");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).isEqualTo("hello-world\n");
        assertThat(result.getStderr()).isEmpty();
        assertThat(result.getDuration()).isNotNull();
    }

    @Test
    @DisplayName("测试异常命令执行：成功捕获非 0 exitCode 与错误输出，checkSuccess 抛出异常")
    void testFailedCommandExecution() {
        SshExecResult result = template.exec("fail_command");

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(result.getStderr()).contains("command failed with error");

        assertThatThrownBy(result::checkSuccess)
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.COMMAND_EXEC_FAILED);
                });
    }

    @Test
    @DisplayName("测试实时流式按行消费：通过 Consumer<String> 实时接收行，防 OOM")
    void testStreamingExecution() {
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();

        int exitCode = template.execStream(
                "stream_test",
                stdoutLines::add,
                stderrLines::add,
                SshExecOptions.defaults()
        );

        assertThat(exitCode).isEqualTo(0);
        assertThat(stdoutLines).containsExactly("stream-line-1", "stream-line-2", "stream-line-3");
        assertThat(stderrLines).isEmpty();
    }

    @Test
    @DisplayName("测试多行脚本执行：正确传递多行内容并完成管道执行")
    void testMultiLineScriptExecution() {
        String script = "echo step1\necho step2\necho step3";
        SshExecResult result = template.execScript(script);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).contains("step1");
        assertThat(result.getStdout()).contains("step2");
        assertThat(result.getStdout()).contains("step3");
    }

    @Test
    @DisplayName("测试声明式工作目录 (workingDir)：前置注入 cd 指令正确切换工作目录")
    void testWorkingDirectoryResolution() {
        SshExecOptions options = SshExecOptions.ofWorkingDir("/var/log/nginx");
        SshExecResult result = template.exec("pwd", options);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("/var/log/nginx");
    }

    @Test
    @DisplayName("测试环境变量注入 (env)：正确在通道中传递并解析环境变量")
    void testEnvironmentVariableInjection() {
        SshExecOptions options = SshExecOptions.builder()
                .env(Map.of("DEPLOY_ENV", "STAGING", "APP_ID", "10086"))
                .build();

        SshExecResult result = template.exec("env_test", options);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).contains("DEPLOY_ENV=STAGING");
        assertThat(result.getStdout()).contains("APP_ID=10086");
    }

    @Test
    @DisplayName("测试 GBK 字符集解码：老旧 Windows/Linux 系统中文输出无乱码")
    void testGbkChineseDecoding() {
        Charset gbk = Charset.forName("GBK");
        SshExecOptions options = SshExecOptions.ofCharset(gbk);

        SshExecResult result = template.exec("gbk_test", options);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("服务启动成功，系统运行正常");
    }

    @Test
    @DisplayName("测试超时孤儿进程主动防卫：命令超时触发 SIGTERM/SIGINT 信号发射并阻断")
    void testTimeoutOrphanProcessGuardAndSignalEmission() {
        SshExecOptions options = SshExecOptions.ofTimeout(Duration.ofMillis(300));

        assertThatThrownBy(() -> template.exec("slow_orphan_task", options))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.COMMAND_TIMEOUT);
                    assertThat(sshEx.getMessage()).contains("超时");
                });

        // 验证远端进程确实接收到了终止信号（SIGTERM 或 SIGINT）
        assertThat(receivedSignal.get()).isIn("TERM", "INT");
    }

    @Test
    @DisplayName("测试通过 SshContextHolder 动态切源执行命令")
    void testContextHolderRouting() {
        SshServerInfo nodeB = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("testuser")
                .password("testpass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();
        registry.register("node-b", nodeB);

        String output = SshContextHolder.runWith("node-b", () -> {
            SshExecResult res = template.exec("echo from-node-b");
            return res.getStdout().trim();
        });

        assertThat(output).isEqualTo("from-node-b");
    }

    @Test
    @DisplayName("测试 execOnce 瞬态隔离执行：执行完毕立即物理断开会话，池化容量为 0")
    void testExecOnceTransientExecution() {
        SshServerInfo transientInfo = SshServerInfo.of("127.0.0.1", serverPort, "testuser", "testpass");

        SshExecResult result = template.execOnce(transientInfo, "echo transient-exec");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("transient-exec");

        // 验证瞬态会话未被缓存至池中
        assertThat(sessionPool.getPoolSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试 execScript 多行脚本跨平台换行符规范化：Windows CRLF 自动清洗为 Unix LF")
    void testExecScriptWindowsCrlfNormalization() {
        String windowsScript = "script_task1\r\nscript_task2\r\n";
        SshExecResult result = template.execScript("default", windowsScript);
        assertThat(result.isSuccess()).isTrue();
        // 验证 Windows CRLF 已被无缝清洗为 Unix LF，绝不包含 \r
        assertThat(result.getStdout()).doesNotContain("\r");
        assertThat(result.getStdout()).contains("script_task1\nscript_task2");
    }

    @Test
    @DisplayName("测试 SshExecOptions 开启 pty 伪终端选项命令执行")
    void testExecWithPtyOption() {
        SshExecOptions options = SshExecOptions.builder()
                .pty(true)
                .build();
        SshExecResult result = template.exec("echo pty-enabled", options);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isEqualTo("pty-enabled");
    }

    @Test
    @DisplayName("测试 SshTemplate.getPoolStats() 暴露会话池统计快照")
    void testGetPoolStats() {
        template.exec("echo stats-1");
        template.exec("echo stats-2");

        SshPoolStats stats = template.getPoolStats();
        assertThat(stats).isNotNull();
        assertThat(stats.getMaxSessions()).isEqualTo(64);
        assertThat(stats.getPoolSize()).isEqualTo(1);
        assertThat(stats.getActiveSessions()).isEqualTo(1);
        assertThat(stats.getTotalCreated()).isEqualTo(1);
        assertThat(stats.getTotalHits()).isEqualTo(1);
        assertThat(stats.getHitRate()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("测试 maxOutputSize 有界输出流防 OOM：超限安全截断并排空通道，返回 truncated=true 与真实退出码")
    void testMaxOutputSizeTruncationAndDrain() {
        // 1. 输出在阈值内：不截断
        SshExecOptions withinLimit = SshExecOptions.builder()
                .maxOutputSize(DataSize.ofBytes(1024))
                .build();
        SshExecResult normal = template.exec("large_output_test", withinLimit);
        assertThat(normal.isSuccess()).isTrue();
        assertThat(normal.isTruncated()).isFalse();
        assertThat(normal.getStdout().getBytes(StandardCharsets.UTF_8).length).isEqualTo(512);

        // 2. 输出超过阈值：仅保留前 64 字节，排空远端流确保进程正常退出并置位 truncated=true
        SshExecOptions smallLimit = SshExecOptions.builder()
                .maxOutputSize(DataSize.ofBytes(64))
                .build();
        SshExecResult truncated = template.exec("large_output_test", smallLimit);
        assertThat(truncated.isSuccess()).isTrue();
        assertThat(truncated.getExitCode()).isZero();
        assertThat(truncated.isTruncated()).isTrue();
        assertThat(truncated.getStdout().getBytes(StandardCharsets.UTF_8).length).isEqualTo(64);
    }

    @Test
    @DisplayName("测试内置 HighRiskCommandInterceptor 高危命令防火墙：精确阻断毁灭性指令并放行安全子路径")
    void testHighRiskCommandGuard() {
        // 1. 内置毁灭性命令阻断（错误码 56017 COMMAND_FORBIDDEN）
        List<String> dangerousCommands = List.of(
                "rm -rf /",
                "rm -rf /*",
                "sudo mkfs.ext4 /dev/sda",
                "dd if=/dev/zero of=/dev/sdb bs=1M",
                ":(){ :|:& };:",
                "shutdown -h now",
                "reboot",
                "sudo reboot",
                "sudo -u root shutdown -h now",
                "echo ok && reboot",
                "ls; halt"
        );
        for (String cmd : dangerousCommands) {
            assertThatThrownBy(() -> template.exec(cmd))
                    .isInstanceOf(SshOperationException.class)
                    .satisfies(ex -> assertThat(((SshOperationException) ex).getResultCode())
                            .isEqualTo(SshResultCode.COMMAND_FORBIDDEN));
        }

        // 2. 安全子路径与参数位关键字不误杀
        List<String> safeCommands = List.of(
                "echo rm -rf /tmp/build-cache",
                "echo rm -rf /",
                "echo reboot",
                "echo shutdown -h now",
                "grep halt /var/log/app.log"
        );
        for (String safeCmd : safeCommands) {
            SshExecResult safeResult = template.exec(safeCmd);
            assertThat(safeResult.isSuccess()).isTrue();
        }

        // 3. 自定义附加黑名单正则阻断
        SshProperties customProps = new SshProperties();
        customProps.setHost("127.0.0.1");
        customProps.setPort(serverPort);
        customProps.setUsername("testuser");
        customProps.setPassword("testpass");
        customProps.setHostKeyPolicy(HostKeyPolicy.IGNORE);
        customProps.setDangerousCommandPatterns(List.of("(?i)\\bdrop\\s+database\\b"));

        SshTemplate customTemplate = new SshTemplate(sessionPool, registry, customProps);
        assertThatThrownBy(() -> customTemplate.exec("mysql -e 'DROP DATABASE prod'"))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> assertThat(((SshOperationException) ex).getResultCode())
                        .isEqualTo(SshResultCode.COMMAND_FORBIDDEN));

        // 4. 非法正则规则在构造期 Fail-Fast 转译为 SshOperationException 并保留 cause
        SshProperties invalidRegexProps = new SshProperties();
        invalidRegexProps.setDangerousCommandPatterns(List.of("[invalid-regex"));
        assertThatThrownBy(() -> new HighRiskCommandInterceptor(invalidRegexProps))
                .isInstanceOf(SshOperationException.class)
                .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    @Test
    @DisplayName("测试 SshCommandInterceptor SPI 执行前后与异常生命周期回调")
    void testCommandInterceptorLifecycle() {
        List<String> events = new ArrayList<>();
        SshCommandInterceptor auditInterceptor = new SshCommandInterceptor() {
            @Override
            public void beforeExecute(SshServerInfo serverInfo, String command, SshExecOptions options) {
                events.add("before:" + command);
            }

            @Override
            public void afterExecute(SshServerInfo serverInfo, String command, SshExecOptions options, SshExecResult result) {
                events.add("after:" + command + ":" + result.getExitCode());
            }

            @Override
            public void onError(SshServerInfo serverInfo, String command, SshExecOptions options, Throwable error) {
                events.add("error:" + command + ":" + error.getClass().getSimpleName());
            }
        };

        SshProperties props = new SshProperties();
        SshTemplate interceptedTemplate = new SshTemplate(
                sessionPool, registry, props, null, null,
                List.of(new HighRiskCommandInterceptor(props), auditInterceptor));

        // 1. 正常执行：触发 before -> after
        SshExecResult ok = interceptedTemplate.exec("echo ok-spi");
        assertThat(ok.isSuccess()).isTrue();
        assertThat(events).containsExactly("before:echo ok-spi", "after:echo ok-spi:0");

        // 2. 流式执行：触发 before -> after
        events.clear();
        int streamCode = interceptedTemplate.execStream("stream_test", line -> {}, null, SshExecOptions.defaults());
        assertThat(streamCode).isZero();
        assertThat(events).containsExactly("before:stream_test", "after:stream_test:0");

        // 3. 高危命令被前置拦截器阻断，且后续异常被 onError 感知
        events.clear();
        assertThatThrownBy(() -> interceptedTemplate.exec("rm -rf /"))
                .isInstanceOf(SshOperationException.class);
        assertThat(events).containsExactly("error:rm -rf /:SshOperationException");
    }

    /**
     * 针对嵌入式 SSH 服务端的命令测试工厂
     */
    private class TestCommandFactory implements CommandFactory {

        @Override
        public Command createCommand(ChannelSession channelSession, String command) {
            return new Command() {
                private OutputStream out;
                private OutputStream err;
                private ExitCallback callback;
                private Thread workerThread;
                private volatile boolean destroyed = false;

                @Override
                public void setInputStream(InputStream in) {
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
                public void start(ChannelSession channel, Environment env) {
                    // 挂载信号监听器
                    env.addSignalListener((ch, signal) -> {
                        receivedSignal.set(signal.name());
                        if (workerThread != null) {
                            workerThread.interrupt();
                        }
                    }, Signal.TERM, Signal.INT);

                    workerThread = new Thread(() -> {
                        try {
                            handleCommand(command, env);
                        } catch (Exception e) {
                            if (callback != null) {
                                callback.onExit(1, e.getMessage());
                            }
                        }
                    }, "test-command-worker");
                    workerThread.start();
                }

                @Override
                public void destroy(ChannelSession channel) {
                    this.destroyed = true;
                    if (workerThread != null) {
                        workerThread.interrupt();
                    }
                }

                private void handleCommand(String cmd, Environment env) throws Exception {
                    String cleanCmd = cmd.trim();

                    if (cleanCmd.startsWith("echo ")) {
                        String msg = cleanCmd.substring("echo ".length()).trim();
                        out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("pwd")) {
                        if (cleanCmd.contains("/var/log/nginx")) {
                            out.write("/var/log/nginx\n".getBytes(StandardCharsets.UTF_8));
                        } else {
                            out.write("/root\n".getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("fail_command")) {
                        err.write("command failed with error\n".getBytes(StandardCharsets.UTF_8));
                        err.flush();
                        callback.onExit(1);
                        return;
                    }

                    if (cleanCmd.contains("stream_test")) {
                        out.write("stream-line-1\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.write("stream-line-2\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.write("stream-line-3\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("echo step1")) {
                        out.write("step1\nstep2\nstep3\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("env_test")) {
                        Map<String, String> currentEnv = env.getEnv();
                        String response = "DEPLOY_ENV=" + currentEnv.getOrDefault("DEPLOY_ENV", "") + "\n" +
                                "APP_ID=" + currentEnv.getOrDefault("APP_ID", "") + "\n";
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("gbk_test")) {
                        Charset gbk = Charset.forName("GBK");
                        out.write("服务启动成功，系统运行正常\n".getBytes(gbk));
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("large_output_test")) {
                        byte[] payload = new byte[512];
                        java.util.Arrays.fill(payload, (byte) 'A');
                        out.write(payload);
                        out.flush();
                        callback.onExit(0);
                        return;
                    }

                    if (cleanCmd.contains("slow_orphan_task")) {
                        try {
                            // 模拟耗时慢任务，直至被外部信号中断
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            // 收到信号后正常退出
                        }
                        if (callback != null) {
                            callback.onExit(130, "慢任务超时被信号终止");
                        }
                        return;
                    }

                    // 默认未知命令回显
                    out.write((cleanCmd + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    callback.onExit(0);
                }
            };
        }
    }
}
