package io.github.atengk.boot.ssh.tunnel;

import io.github.atengk.boot.ssh.model.SshServerInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 本地端口转发受管句柄 (SshTunnelHandle)
 * <p>
 * 1. 纳管本地端口至远端服务之间建立的 SSH 端口转发通道，原生实现 {@link AutoCloseable} 接口；<br>
 * 2. 支持获取动态分配绑定的本地端口（当指定 0 时操作系统自动分配）、远端目标及跳板机元数据；<br>
 * 3. {@link #close()} 保证幂等性与确定性物理资源清理，释放底层本地 Socket 监听端口；<br>
 * 4. 深度联动生命周期协调器，保障在 Spring 容器停机阶段优先优雅注销，杜绝端口悬挂。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshTunnelHandle implements AutoCloseable {

    @Getter
    private final SshServerInfo serverInfo;

    private final ExplicitPortForwardingTracker tracker;

    @Getter
    private final String localHost;

    @Getter
    private final int localPort;

    @Getter
    private final String remoteHost;

    @Getter
    private final int remotePort;

    @Getter
    private final long creationTime;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Consumer<SshTunnelHandle> closeCallback;

    /**
     * 构造受管本地端口转发句柄
     *
     * @param serverInfo    中继跳板机或目标服务器描述符
     * @param tracker       底层 MINA SSHD 端口转发跟踪器
     * @param localHost     本地监听主机
     * @param localPort     本地监听实际端口
     * @param remoteHost    目标远端主机
     * @param remotePort    目标远端端口
     * @param closeCallback 关闭时的清理回调（可为 null）
     */
    public SshTunnelHandle(SshServerInfo serverInfo,
                           ExplicitPortForwardingTracker tracker,
                           String localHost,
                           int localPort,
                           String remoteHost,
                           int remotePort,
                           Consumer<SshTunnelHandle> closeCallback) {
        Assert.notNull(tracker, "ExplicitPortForwardingTracker must not be null");
        Assert.hasText(remoteHost, "remoteHost must not be blank");
        Assert.isTrue(localPort > 0 && localPort <= 65535, "localPort must be between 1 and 65535");
        Assert.isTrue(remotePort > 0 && remotePort <= 65535, "remotePort must be between 1 and 65535");

        this.serverInfo = serverInfo;
        this.tracker = tracker;
        this.localHost = (localHost != null && !localHost.isBlank()) ? localHost : "127.0.0.1";
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.creationTime = System.currentTimeMillis();
        this.closeCallback = closeCallback;
    }

    /**
     * 判断当前本地端口转发隧道是否处于存活且可用状态
     *
     * @return true 若隧道与底层会话均处于健康开放状态
     */
    public boolean isAlive() {
        if (closed.get()) {
            return false;
        }
        if (tracker == null || !tracker.isOpen()) {
            return false;
        }
        ClientSession session = tracker.getSession();
        return session != null && session.isOpen() && !session.isClosing() && !session.isClosed();
    }

    /**
     * 判断隧道是否已关闭
     *
     * @return true 若已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 确定性关闭本地端口转发隧道并释放监听端口（幂等防卫）
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (tracker != null && tracker.isOpen()) {
                    tracker.close();
                    log.debug("ateng-boot-ssh: 本地端口转发隧道已安全关闭 [{}:{} -> {}:{}]",
                            localHost, localPort, remoteHost, remotePort);
                }
            } catch (IOException e) {
                log.warn("ateng-boot-ssh: 关闭本地端口转发隧道异常 [{}:{} -> {}:{}]: {}",
                        localHost, localPort, remoteHost, remotePort, e.getMessage());
            } finally {
                if (closeCallback != null) {
                    try {
                        closeCallback.accept(this);
                    } catch (Exception ex) {
                        log.debug("ateng-boot-ssh: 执行隧道关闭回调异常: {}", ex.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "SshTunnelHandle(" +
                "local=" + localHost + ":" + localPort +
                " -> remote=" + remoteHost + ":" + remotePort +
                ", via=" + (serverInfo != null ? serverInfo.toSessionKey() : "unknown") +
                ", alive=" + isAlive() +
                ')';
    }
}
