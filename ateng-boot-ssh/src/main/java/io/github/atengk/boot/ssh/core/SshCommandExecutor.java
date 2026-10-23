package io.github.atengk.boot.ssh.core;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.apache.sshd.common.util.buffer.Buffer;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SSH 远程命令执行底层核心调度引擎
 * <p>
 * 1. 统一调度同步命令执行、实时按行流式消费与多行脚本管道执行；<br>
 * 2. 严格遵循因果异常链，纳管工作目录（workingDir）、环境变量注入（env）与编码字符集（GBK/UTF-8）；<br>
 * 3. 落地【超时孤儿进程信号主动防卫】：超时发生时在关闭通道前主动向服务端发送 SIGTERM / SIGINT 信号包，杜绝远端僵尸进程失控。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public final class SshCommandExecutor {

    private static final Duration DEFAULT_SIGNAL_GRACE_PERIOD = Duration.ofMillis(500);

    private SshCommandExecutor() {
    }

    /**
     * 同步执行单条远程命令并捕获完整输出结果
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待执行的原始命令
     * @param options        扩展配置选项（可为 null）
     * @param defaultTimeout 默认超时时长
     * @return 强类型命令执行结果
     */
    /**
     * 同步执行单条远程命令并捕获完整输出结果
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待执行的原始命令
     * @param options        扩展配置选项（可为 null）
     * @param defaultTimeout 默认超时时长
     * @return 强类型命令执行结果
     */
    public static SshExecResult executeSync(ClientSession session,
                                            String rawCommand,
                                            SshExecOptions options,
                                            Duration defaultTimeout) {
        return executeSyncWithStdin(session, rawCommand, null, options, defaultTimeout);
    }

    /**
     * 同步执行远程命令（支持向通道标准输入写入字节流，如 sudo 提权密码管道）
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待执行的原始命令
     * @param stdinBytes     标准输入字节数据（写入后立即冲刷关闭输入管道，可为 null）
     * @param options        扩展配置选项（可为 null）
     * @param defaultTimeout 默认超时时长
     * @return 强类型命令执行结果
     */
    public static SshExecResult executeSyncWithStdin(ClientSession session,
                                                     String rawCommand,
                                                     byte[] stdinBytes,
                                                     SshExecOptions options,
                                                     Duration defaultTimeout) {
        Assert.notNull(session, "ClientSession must not be null");
        Assert.hasText(rawCommand, "command must not be blank");

        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        Charset charset = effectiveOptions.getCharset() != null ? effectiveOptions.getCharset() : StandardCharsets.UTF_8;
        Duration timeout = resolveTimeout(effectiveOptions, defaultTimeout);
        String finalCommand = buildEffectiveCommand(rawCommand, effectiveOptions);

        long startTime = System.currentTimeMillis();
        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

        ChannelExec channel = null;
        try {
            PtyChannelConfiguration ptyConfig = effectiveOptions.isPty() ? new PtyChannelConfiguration() : null;
            channel = session.createExecChannel(finalCommand, charset, ptyConfig, effectiveOptions.getEnv());
            channel.setOut(stdoutStream);
            channel.setErr(stderrStream);

            // 注入环境变量
            applyEnvironmentVariables(channel, effectiveOptions.getEnv());

            // 开启通道并等待服务端确认
            channel.open().verify(timeout);

            // 若存在标准输入数据，安全写入并立即冲刷关闭管道
            if (stdinBytes != null && stdinBytes.length > 0) {
                try (OutputStream in = channel.getInvertedIn()) {
                    if (in != null) {
                        in.write(stdinBytes);
                        in.flush();
                    }
                }
            }

            // 等待远程进程执行结束
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);

            // 超时检测与孤儿进程信号主动防卫
            if (!events.contains(ClientChannelEvent.CLOSED)) {
                handleExecutionTimeout(channel, rawCommand, timeout);
            }

            Integer exitStatus = channel.getExitStatus();
            int exitCode = exitStatus != null ? exitStatus : 0;
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);

            String stdout = stdoutStream.toString(charset);
            String stderr = stderrStream.toString(charset);

            return SshExecResult.builder()
                    .exitCode(exitCode)
                    .stdout(stdout)
                    .stderr(stderr)
                    .duration(duration)
                    .build();
        } catch (SshOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.COMMAND_EXEC_FAILED, "SSH 远程命令执行异常: " + e.getMessage(), e);
        } finally {
            closeChannelQuietly(channel);
        }
    }

    /**
     * 执行原生 Sudo 提权命令（基于 -S -p '' 标准输入安全管道与即时内存擦除抹零）
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待以管理员权限执行的命令
     * @param sudoPassword   提权密码（char 数组，传输完毕后由系统无条件就地擦除清零）
     * @param options        扩展配置选项
     * @param defaultTimeout 默认超时时长
     * @return 强类型命令执行结果
     */
    public static SshExecResult executeSudo(ClientSession session,
                                            String rawCommand,
                                            char[] sudoPassword,
                                            SshExecOptions options,
                                            Duration defaultTimeout) {
        Assert.notNull(session, "ClientSession must not be null");
        Assert.hasText(rawCommand, "command must not be blank");

        String sudoCommand = buildSudoCommand(rawCommand);
        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        Charset charset = effectiveOptions.getCharset() != null ? effectiveOptions.getCharset() : StandardCharsets.UTF_8;

        byte[] passwordBytes = null;
        try {
            if (sudoPassword != null && sudoPassword.length > 0) {
                passwordBytes = encodePasswordWithNewline(sudoPassword, charset);
            }
            return executeSyncWithStdin(session, sudoCommand, passwordBytes, effectiveOptions, defaultTimeout);
        } finally {
            // 提权凭据内存绝对擦除抹零 (In-Memory Scrubbing)
            if (passwordBytes != null) {
                Arrays.fill(passwordBytes, (byte) 0);
            }
            if (sudoPassword != null) {
                Arrays.fill(sudoPassword, '\0');
            }
        }
    }

    /**
     * 执行原生 Sudo 提权命令（字符串密码重载）
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待以管理员权限执行的命令
     * @param sudoPassword   提权密码字符串
     * @param options        扩展配置选项
     * @param defaultTimeout 默认超时时长
     * @return 强类型命令执行结果
     */
    public static SshExecResult executeSudo(ClientSession session,
                                            String rawCommand,
                                            String sudoPassword,
                                            SshExecOptions options,
                                            Duration defaultTimeout) {
        char[] chars = sudoPassword != null ? sudoPassword.toCharArray() : null;
        return executeSudo(session, rawCommand, chars, options, defaultTimeout);
    }

    /**
     * 构建非交互式标准 sudo 命令格式：自动挂载 -S（从标准输入读取密码）与 -p ''（屏蔽密码提示）
     *
     * @param rawCommand 原始命令
     * @return 规整后的 sudo 执行命令
     */
    public static String buildSudoCommand(String rawCommand) {
        Assert.hasText(rawCommand, "command must not be blank");
        String trimmed = rawCommand.trim();
        if (trimmed.startsWith("sudo -S -p '' ")) {
            return trimmed;
        }
        if (trimmed.startsWith("sudo ")) {
            return "sudo -S -p '' " + trimmed.substring(5).trim();
        }
        return "sudo -S -p '' " + trimmed;
    }

    private static byte[] encodePasswordWithNewline(char[] password, Charset charset) {
        ByteBuffer byteBuffer = charset.encode(CharBuffer.wrap(password));
        int len = byteBuffer.remaining();
        byte[] bytes = new byte[len + 1];
        byteBuffer.get(bytes, 0, len);
        bytes[len] = '\n';
        return bytes;
    }

    /**
     * 实时按行流式消费执行远程命令（防大日志 OOM）
     *
     * @param session        活跃客户端会话
     * @param rawCommand     待执行的原始命令
     * @param stdoutConsumer 标准输出按行回调
     * @param stderrConsumer 错误输出按行回调
     * @param options        扩展配置选项
     * @param defaultTimeout 默认超时时长
     * @return 进程退出状态码
     */
    public static int executeStream(ClientSession session,
                                    String rawCommand,
                                    Consumer<String> stdoutConsumer,
                                    Consumer<String> stderrConsumer,
                                    SshExecOptions options,
                                    Duration defaultTimeout) {
        Assert.notNull(session, "ClientSession must not be null");
        Assert.hasText(rawCommand, "command must not be blank");

        SshExecOptions effectiveOptions = options != null ? options : SshExecOptions.defaults();
        Charset charset = effectiveOptions.getCharset() != null ? effectiveOptions.getCharset() : StandardCharsets.UTF_8;
        Duration timeout = resolveTimeout(effectiveOptions, defaultTimeout);
        String finalCommand = buildEffectiveCommand(rawCommand, effectiveOptions);

        LineStreamingOutputStream stdoutStream = new LineStreamingOutputStream(stdoutConsumer, charset);
        LineStreamingOutputStream stderrStream = new LineStreamingOutputStream(stderrConsumer, charset);

        ChannelExec channel = null;
        try {
            PtyChannelConfiguration ptyConfig = effectiveOptions.isPty() ? new PtyChannelConfiguration() : null;
            channel = session.createExecChannel(finalCommand, charset, ptyConfig, effectiveOptions.getEnv());
            channel.setOut(stdoutStream);
            channel.setErr(stderrStream);

            applyEnvironmentVariables(channel, effectiveOptions.getEnv());
            channel.open().verify(timeout);

            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            if (!events.contains(ClientChannelEvent.CLOSED)) {
                handleExecutionTimeout(channel, rawCommand, timeout);
            }

            // 刷出残余尾行
            stdoutStream.flush();
            stderrStream.flush();

            Integer exitStatus = channel.getExitStatus();
            return exitStatus != null ? exitStatus : 0;
        } catch (SshOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.COMMAND_EXEC_FAILED, "SSH 流式执行命令异常: " + e.getMessage(), e);
        } finally {
            closeChannelQuietly(channel);
        }
    }

    /**
     * 构建最终附带工作目录的命令字符串
     *
     * @param rawCommand 原始命令
     * @param options    参数选项
     * @return 最终组装的完整命令
     */
    public static String buildEffectiveCommand(String rawCommand, SshExecOptions options) {
        if (options == null || options.getWorkingDir() == null || options.getWorkingDir().isBlank()) {
            return rawCommand;
        }
        String workingDir = options.getWorkingDir().trim();
        // 自动注入切换工作目录前置指令
        return "cd " + quotePath(workingDir) + " && ( " + rawCommand + " )";
    }

    /**
     * 主动向远端进程发送终止信号包（RFC 4254 signal request）
     *
     * @param channel    当前执行通道
     * @param signalName 信号名称（如 "TERM", "INT", "KILL"）
     */
    public static void sendSignal(ChannelExec channel, String signalName) {
        try {
            if (channel != null && channel.isOpen()) {
                Buffer buffer = channel.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
                buffer.putUInt(channel.getRecipient());
                buffer.putString("signal");
                buffer.putBoolean(false); // 不需要服务端回复
                buffer.putString(signalName);
                channel.writePacket(buffer);
                log.info("ateng-boot-ssh: 超时防卫！已主动向远端进程发送终止信号 [{}]", signalName);
            }
        } catch (Exception e) {
            log.warn("ateng-boot-ssh: 发送远程信号 [{}] 异常: {}", signalName, e.getMessage());
        }
    }

    private static void handleExecutionTimeout(ChannelExec channel, String rawCommand, Duration timeout) {
        log.error("ateng-boot-ssh: 远程命令执行超时 [timeout={}], 启动孤儿进程终止防卫 [command={}]", timeout, rawCommand);

        // 1. 发送 SIGTERM 优雅终止信号
        sendSignal(channel, "TERM");

        // 2. 留出宽限时间等待子进程自行退出
        try {
            Set<ClientChannelEvent> graceEvents = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), DEFAULT_SIGNAL_GRACE_PERIOD);
            if (!graceEvents.contains(ClientChannelEvent.CLOSED)) {
                // 3. 仍未退出，发送 SIGINT 进一步防卫
                sendSignal(channel, "INT");
            }
        } catch (Exception ignored) {
        }

        // 4. 彻底物理关闭通道
        try {
            channel.close(true);
        } catch (Exception ignored) {
        }

        throw new SshOperationException(
                SshResultCode.COMMAND_TIMEOUT,
                "SSH 远程命令执行超时 [timeout=" + timeout.toSeconds() + "s]: " + rawCommand
        );
    }

    private static void applyEnvironmentVariables(ChannelExec channel, Map<String, String> env) {
        if (env != null && !env.isEmpty()) {
            env.forEach(channel::setEnv);
        }
    }

    private static Duration resolveTimeout(SshExecOptions options, Duration defaultTimeout) {
        if (options != null && options.getTimeout() != null && !options.getTimeout().isZero()) {
            return options.getTimeout();
        }
        return defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(60);
    }

    private static String quotePath(String path) {
        if (path.contains(" ") && !path.startsWith("'") && !path.startsWith("\"")) {
            return "'" + path + "'";
        }
        return path;
    }

    private static void closeChannelQuietly(ChannelExec channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close(false);
            } catch (Exception e) {
                log.debug("ateng-boot-ssh: 关闭 ChannelExec 异常: {}", e.getMessage());
            }
        }
    }
}
