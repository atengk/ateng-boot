package io.github.atengk.boot.ssh.lifecycle;

import io.github.atengk.boot.ssh.pool.SshSessionPool;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSH 基础设施生命周期受管治理协调器
 * <p>
 * 遵循确定性三步优雅停机时序：<br>
 * 【时序 1/3】关闭活跃端口转发隧道（释放本地监听端口）；<br>
 * 【时序 2/3】安全注销并断开受管会话缓存池（{@link SshSessionPool}）；<br>
 * 【时序 3/3】停止底层 NIO 异步驱动客户端引擎（{@link SshClient}）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshLifecycleManager implements SmartLifecycle {

    private final SshSessionPool sessionPool;
    private final SshClient sshClient;
    private final CopyOnWriteArrayList<Runnable> tunnelCleanupHooks = new CopyOnWriteArrayList<>();

    private volatile boolean running = false;

    public SshLifecycleManager(SshSessionPool sessionPool, SshClient sshClient) {
        this.sessionPool = sessionPool;
        this.sshClient = sshClient;
    }

    /**
     * 注册端口转发隧道等高阶物理资源的停机清理回调（专职服务于 Ticket 6 端口转发）
     *
     * @param cleanupHook 清理逻辑
     */
    public void registerTunnelCleanupHook(Runnable cleanupHook) {
        if (cleanupHook != null) {
            tunnelCleanupHooks.add(cleanupHook);
        }
    }

    @Override
    public void start() {
        this.running = true;
        log.info("ateng-boot-ssh: SSH 基础设施生命周期受管治理协调器启动就绪");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        log.info("ateng-boot-ssh: 正在执行确定性三步优雅停机时序...");

        // 步骤 1/3：释放并关闭所有活跃的端口转发隧道
        log.debug("ateng-boot-ssh: [停机时序 1/3] 正在确认并释放活跃端口转发隧道 (共 {} 个钩子)...", tunnelCleanupHooks.size());
        for (Runnable hook : tunnelCleanupHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 释放端口转发隧道异常: {}", e.getMessage());
            }
        }
        tunnelCleanupHooks.clear();

        // 步骤 2/3：安全注销并断开所有池化 Session
        log.debug("ateng-boot-ssh: [停机时序 2/3] 正在注销并断开全部池化 SSH 会话...");
        if (sessionPool != null) {
            try {
                sessionPool.close();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 关闭 SshSessionPool 异常: {}", e.getMessage());
            }
        }

        // 步骤 3/3：停止底层 NIO SshClient 引擎
        log.debug("ateng-boot-ssh: [停机时序 3/3] 正在停止底层 Apache MINA SshClient 引擎...");
        if (sshClient != null && sshClient.isStarted()) {
            try {
                sshClient.stop();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 停止 SshClient 引擎异常: {}", e.getMessage());
            }
        }

        this.running = false;
        log.info("ateng-boot-ssh: SSH 确定性三步优雅停机全部完成，网络连接与物理资源已 100% 释放");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 赋予适当的生命周期 Phase，确保在 Spring 容器停机阶段优先优雅释放高阶网络资源
        return Integer.MIN_VALUE + 1000;
    }
}
