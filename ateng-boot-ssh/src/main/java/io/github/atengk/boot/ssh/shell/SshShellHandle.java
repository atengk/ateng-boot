package io.github.atengk.boot.ssh.shell;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.model.SshShellOptions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.session.Session;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 交互式 PTY 伪终端受管句柄 (SshShellHandle)
 * <p>
 * 1. 封装底层 Apache MINA SSHD {@link ChannelShell} 伪终端会话，原生实现 {@link AutoCloseable}；<br>
 * 2. 采用原生二进制字节流数据模型（{@code sendInput} / {@code onOutput}），保障 ANSI 控制序列与颜色无损传输；<br>
 * 3. 支持窗口自适应动态调整（{@link #resize(int, int)}），底层封装发送 {@code SSH_MSG_CHANNEL_WINDOW_CHANGE}；<br>
 * 4. 支持远端进程退出状态监听（{@link #onExit(Consumer)}）与通道关闭监听（{@link #onClose(Runnable)}）；<br>
 * 5. 深度融入生命周期受管治理协调器，保障在应用优雅停机阶段确定性释放终端物理连接。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshShellHandle implements AutoCloseable {

    @Getter
    private final SshServerInfo serverInfo;

    private final ChannelShell channel;

    @Getter
    private final SshShellOptions options;

    @Getter
    private final long creationTime;

    private volatile OutputStream in;

    private final CopyOnWriteArrayList<Consumer<byte[]>> outputListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Integer>> exitCallbacks = new ArrayList<>();
    private final List<Runnable> closeCallbacks = new ArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean exitTriggered = new AtomicBoolean(false);
    private final AtomicBoolean closeTriggered = new AtomicBoolean(false);
    private final AtomicReference<Integer> cachedExitStatus = new AtomicReference<>(null);

    private final Consumer<SshShellHandle> lifecycleUnregisterCallback;

    /**
     * 构造交互式 PTY 伪终端受管句柄
     *
     * @param serverInfo                  目标服务器描述符
     * @param channel                     底层已开启或待开启的 ChannelShell
     * @param options                     终端规格配置选项
     * @param lifecycleUnregisterCallback 停机生命周期注销回调
     */
    public SshShellHandle(SshServerInfo serverInfo,
                          ChannelShell channel,
                          SshShellOptions options,
                          Consumer<SshShellHandle> lifecycleUnregisterCallback) {
        Assert.notNull(channel, "ChannelShell must not be null");
        this.serverInfo = serverInfo;
        this.channel = channel;
        this.options = options != null ? options : SshShellOptions.defaults();
        this.creationTime = System.currentTimeMillis();
        this.lifecycleUnregisterCallback = lifecycleUnregisterCallback;

        // 绑定输出流分发器：将远端终端回显及控制台输出即时多路广播给各监听器
        OutputStream forwardingStream = new ShellForwardingOutputStream();
        this.channel.setOut(forwardingStream);
        this.channel.setErr(forwardingStream);

        // 监听通道关闭与退出事件
        bindChannelListeners();

        // 获取向远端终端写入二进制数据的输入流
        this.in = channel.getInvertedIn();
    }

    /**
     * 向远端伪终端写入原始二进制字节流数据
     *
     * @param data 字节数据（如用户键盘敲击、控制键或粘贴内容）
     */
    public void sendInput(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        sendInput(data, 0, data.length);
    }

    /**
     * 向远端伪终端写入原始二进制字节流切片
     *
     * @param data 字节数组
     * @param off  起始偏移量
     * @param len  写入长度
     */
    public void sendInput(byte[] data, int off, int len) {
        Assert.notNull(data, "data must not be null");
        Assert.isTrue(off >= 0, "off must be non-negative: " + off);
        Assert.isTrue(len >= 0, "len must be non-negative: " + len);
        Assert.isTrue(off + len <= data.length, "off + len must not exceed data length: " + (off + len) + " > " + data.length);
        if (len == 0) {
            return;
        }
        if (!isAlive()) {
            throw new SshOperationException(SshResultCode.SHELL_OPERATION_FAILED,
                    "SSH 终端通道已关闭，无法发送输入数据");
        }
        OutputStream outStream = getEffectiveIn();
        if (outStream == null) {
            throw new SshOperationException(SshResultCode.SHELL_OPERATION_FAILED,
                    "SSH 终端输入管道尚未就绪或已关闭");
        }
        synchronized (this) {
            try {
                outStream.write(data, off, len);
                outStream.flush();
            } catch (IOException e) {
                throw new SshOperationException(SshResultCode.SHELL_OPERATION_FAILED,
                        "向 SSH 伪终端发送字节流失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 向远端伪终端发送文本字符串（便捷重载，默认以 UTF-8 编码为字节传输）
     *
     * @param text 输入文本字符串（如敲击回车换行或普通命令输入）
     */
    public void sendInput(String text) {
        sendInput(text, StandardCharsets.UTF_8);
    }

    /**
     * 向远端伪终端发送文本字符串（支持指定字符集）
     *
     * @param text    输入文本字符串
     * @param charset 字符集编码（如 GBK）
     */
    public void sendInput(String text, Charset charset) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        byte[] bytes = text.getBytes(effectiveCharset);
        sendInput(bytes);
    }

    /**
     * 注册远端终端输出监听器（支持链式调用与多监听器多路分发）
     *
     * @param consumer 接收远端二进制字节流的消费函数
     * @return 当前句柄
     */
    public SshShellHandle onOutput(Consumer<byte[]> consumer) {
        if (consumer != null) {
            outputListeners.add(consumer);
        }
        return this;
    }

    /**
     * 移除已注册的远端终端输出监听器
     *
     * @param consumer 待移除的监听器函数
     * @return true 若成功移除
     */
    public boolean removeOutputListener(Consumer<byte[]> consumer) {
        return consumer != null && outputListeners.remove(consumer);
    }

    /**
     * 动态自适应调整伪终端视口字符行列尺寸（发送 SSH_MSG_CHANNEL_WINDOW_CHANGE 协议包）
     *
     * @param cols 字符列宽（大于 0）
     * @param rows 字符行高（大于 0）
     */
    public void resize(int cols, int rows) {
        resize(cols, rows, 0, 0);
    }

    /**
     * 动态自适应调整伪终端字符与像素视口尺寸
     *
     * @param cols   字符列宽（大于 0）
     * @param rows   字符行高（大于 0）
     * @param width  像素宽度（大于等于 0）
     * @param height 像素高度（大于等于 0）
     */
    public void resize(int cols, int rows, int width, int height) {
        Assert.isTrue(cols > 0, "cols must be greater than 0");
        Assert.isTrue(rows > 0, "rows must be greater than 0");
        Assert.isTrue(width >= 0, "width must be non-negative");
        Assert.isTrue(height >= 0, "height must be non-negative");

        if (!isAlive()) {
            throw new SshOperationException(SshResultCode.SHELL_OPERATION_FAILED,
                    "SSH 终端通道已关闭，无法调整视口尺寸");
        }
        try {
            channel.sendWindowChange(cols, rows, width, height);
            log.debug("ateng-boot-ssh: PTY 终端尺寸已自适应调整 [cols={}, rows={}, width={}, height={}]",
                    cols, rows, width, height);
        } catch (IOException e) {
            throw new SshOperationException(SshResultCode.SHELL_OPERATION_FAILED,
                    "调整 SSH 伪终端窗口尺寸失败: " + e.getMessage(), e);
        }
    }

    /**
     * 注册远端进程退出回调（当远端返回退出状态码时触发）
     * <p>
     * 具备即时回放语义：若通道此前已完成退出，新注册的回调将安全立即触发执行。
     * </p>
     *
     * @param callback 退出码消费回调
     * @return 当前句柄
     */
    public SshShellHandle onExit(Consumer<Integer> callback) {
        if (callback == null) {
            return this;
        }
        boolean runImmediately = false;
        Integer statusToRun = null;
        synchronized (exitCallbacks) {
            if (exitTriggered.get()) {
                runImmediately = true;
                statusToRun = cachedExitStatus.get();
            } else {
                exitCallbacks.add(callback);
            }
        }
        if (runImmediately && statusToRun != null) {
            try {
                callback.accept(statusToRun);
            } catch (Exception ex) {
                log.debug("ateng-boot-ssh: 执行终端即时退出回调异常: {}", ex.getMessage());
            }
        }
        return this;
    }

    /**
     * 注册终端通道关闭回调（当通道被关闭时触发）
     * <p>
     * 具备即时回放语义：若通道此前已完成关闭，新注册的回调将安全立即触发执行。
     * </p>
     *
     * @param callback 关闭执行动作
     * @return 当前句柄
     */
    public SshShellHandle onClose(Runnable callback) {
        if (callback == null) {
            return this;
        }
        boolean runImmediately = false;
        synchronized (closeCallbacks) {
            if (closeTriggered.get()) {
                runImmediately = true;
            } else {
                closeCallbacks.add(callback);
            }
        }
        if (runImmediately) {
            try {
                callback.run();
            } catch (Exception ex) {
                log.debug("ateng-boot-ssh: 执行终端即时关闭回调异常: {}", ex.getMessage());
            }
        }
        return this;
    }

    /**
     * 获取远端 Shell 进程退出状态码
     *
     * @return 退出码（若进程未退出则为 null）
     */
    public Integer getExitStatus() {
        return channel != null ? channel.getExitStatus() : null;
    }

    /**
     * 判断当前终端通道与底层物理会话是否处于存活可用状态
     *
     * @return true 若处于健康活跃状态
     */
    public boolean isAlive() {
        if (closed.get()) {
            return false;
        }
        if (channel == null || !channel.isOpen() || channel.isClosing() || channel.isClosed()) {
            return false;
        }
        Session session = channel.getSession();
        return session != null && session.isOpen() && !session.isClosing() && !session.isClosed();
    }

    /**
     * 判断当前终端是否已被关闭
     *
     * @return true 若已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 确定性关闭伪终端会话并释放底层通道资源（幂等防卫）
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                // 1. 关闭输入流
                OutputStream outStream = getEffectiveIn();
                if (outStream != null) {
                    try {
                        outStream.close();
                    } catch (IOException ignored) {
                    }
                }
                // 2. 关闭底层 ChannelShell
                if (channel != null && channel.isOpen()) {
                    channel.close(false);
                    log.debug("ateng-boot-ssh: 交互式 PTY 伪终端通道已安全关闭 [{}]",
                            serverInfo != null ? serverInfo.toSessionKey() : "unknown");
                }
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 关闭 PTY 伪终端通道异常: {}", e.getMessage());
            } finally {
                // 3. 触发退出与关闭回调
                triggerExitAndCloseCallbacks();

                // 4. 反向生命周期注销
                if (lifecycleUnregisterCallback != null) {
                    try {
                        lifecycleUnregisterCallback.accept(this);
                    } catch (Exception ex) {
                        log.debug("ateng-boot-ssh: 执行终端生命周期注销回调异常: {}", ex.getMessage());
                    }
                }
            }
        }
    }

    private OutputStream getEffectiveIn() {
        if (this.in == null && channel != null) {
            this.in = channel.getInvertedIn();
        }
        return this.in;
    }

    private void bindChannelListeners() {
        channel.addCloseFutureListener(future -> triggerExitAndCloseCallbacks());
        channel.addChannelListener(new ChannelListener() {
            @Override
            public void channelClosed(Channel ch, Throwable reason) {
                triggerExitAndCloseCallbacks();
            }
        });
    }

    private void triggerExitAndCloseCallbacks() {
        triggerExitCallbacks();
        triggerCloseCallbacks();
    }

    private void triggerExitCallbacks() {
        Integer exitStatus = getExitStatus();
        if (exitStatus == null) {
            return;
        }
        List<Consumer<Integer>> toRun = new ArrayList<>();
        synchronized (exitCallbacks) {
            if (exitTriggered.compareAndSet(false, true)) {
                cachedExitStatus.set(exitStatus);
                toRun.addAll(exitCallbacks);
                exitCallbacks.clear();
            }
        }
        for (Consumer<Integer> callback : toRun) {
            try {
                callback.accept(exitStatus);
            } catch (Exception ex) {
                log.debug("ateng-boot-ssh: 触发终端退出回调异常: {}", ex.getMessage());
            }
        }
    }

    private void triggerCloseCallbacks() {
        List<Runnable> toRun = new ArrayList<>();
        synchronized (closeCallbacks) {
            if (closeTriggered.compareAndSet(false, true)) {
                toRun.addAll(closeCallbacks);
                closeCallbacks.clear();
            }
        }
        for (Runnable callback : toRun) {
            try {
                callback.run();
            } catch (Exception ex) {
                log.debug("ateng-boot-ssh: 触发终端关闭回调异常: {}", ex.getMessage());
            }
        }
    }

    private void broadcastOutput(byte[] chunk) {
        for (Consumer<byte[]> listener : outputListeners) {
            try {
                listener.accept(chunk);
            } catch (Throwable t) {
                log.warn("ateng-boot-ssh: 广播终端输出数据异常: {}", t.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "SshShellHandle(" +
                "server=" + (serverInfo != null ? serverInfo.toSessionKey() : "unknown") +
                ", term=" + options.getTerm() +
                ", size=" + options.getCols() + "x" + options.getRows() +
                ", alive=" + isAlive() +
                ')';
    }

    /**
     * 内部转发输出流，将远端终端回传的字节数组即时广播给订阅者
     */
    private class ShellForwardingOutputStream extends OutputStream {

        @Override
        public void write(int b) {
            byte[] single = new byte[]{(byte) b};
            broadcastOutput(single);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (b == null || len <= 0) {
                return;
            }
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            broadcastOutput(copy);
        }

        @Override
        public void flush() {
            // NIO 驱动通道无需额外冲刷
        }

        @Override
        public void close() {
            // 依赖外层 Channel 控制闭合
        }
    }
}
