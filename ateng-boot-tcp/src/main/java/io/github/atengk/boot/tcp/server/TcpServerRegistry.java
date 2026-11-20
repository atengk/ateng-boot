package io.github.atengk.boot.tcp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TCP 多端口多协议服务端注册表
 * <p>
 * 集中管理应用内启动的多个独立 TCP 服务端实例，支持多端口拓扑、独立协议分帧与统一生命周期管控。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpServerRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpServerRegistry.class);

    /**
     * 默认主服务端名称标识
     */
    public static final String DEFAULT_SERVER_NAME = "default";

    private final Map<String, TcpServer> servers = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean running = false;

    /**
     * 注册 TCP 服务端实例（若存在同名不同引用的运行中旧实例，将自动平滑停止旧实例以防资源泄漏）
     *
     * @param name   服务端唯一名称
     * @param server 服务端实例
     */
    public void register(String name, TcpServer server) {
        Assert.hasText(name, "服务端名称不能为空");
        Assert.notNull(server, "服务端实例不能为空");
        TcpServer previous = servers.put(name, server);
        if (previous != null && previous != server && previous.isRunning()) {
            log.info("检测到同名 TCP 服务端覆盖注册，正在平滑停止旧实例: name={}", name);
            previous.stop();
        }
        log.info("已注册 TCP 服务端实例: name={}, host={}, port={}",
                name, server.getProperties().getHost(), server.getProperties().getPort());
    }

    /**
     * 注销并移除指定名称的 TCP 服务端实例（若处于运行态将自动平滑停止）
     *
     * @param name 服务端名称
     * @return 被移除的服务端实例，不存在时返回 null
     */
    public TcpServer unregister(String name) {
        if (name == null) {
            return null;
        }
        TcpServer removed = servers.remove(name);
        if (removed != null && removed.isRunning()) {
            log.info("注销 TCP 服务端实例，正在平滑停止: name={}", name);
            removed.stop();
        }
        return removed;
    }

    /**
     * 判断是否已注册指定名称的 TCP 服务端实例
     *
     * @param name 服务端名称
     * @return true 表示存在
     */
    public boolean contains(String name) {
        return name != null && servers.containsKey(name);
    }

    /**
     * 获取指定名称的 TCP 服务端实例
     *
     * @param name 服务端名称
     * @return 服务端实例，不存在时返回 null
     */
    public TcpServer getServer(String name) {
        if (name == null) {
            return null;
        }
        return servers.get(name);
    }

    /**
     * 获取指定名称的 TCP 服务端实例，不存在时抛出异常
     *
     * @param name 服务端名称
     * @return 服务端实例
     */
    public TcpServer getRequiredServer(String name) {
        TcpServer server = getServer(name);
        if (server == null) {
            throw new IllegalArgumentException("未找到名称为 [" + name + "] 的 TCP 服务端实例，已注册列表: " + servers.keySet());
        }
        return server;
    }

    /**
     * 获取默认主服务端实例
     *
     * @return 默认服务端实例，不存在时返回 null
     */
    public TcpServer getDefaultServer() {
        return getServer(DEFAULT_SERVER_NAME);
    }

    /**
     * 获取已注册的全部服务端只读映射表
     *
     * @return 不可变映射表
     */
    public Map<String, TcpServer> getAllServers() {
        return Collections.unmodifiableMap(servers);
    }

    /**
     * 获取指定服务端的实际监听端口
     *
     * @param name 服务端名称
     * @return 实际监听端口
     */
    public int getActualPort(String name) {
        return getRequiredServer(name).getActualPort();
    }

    @Override
    public void start() {
        lifecycleLock.lock();
        try {
            if (running) {
                return;
            }
            log.info("正在启动 TCP 服务端注册表，共纳管 {} 个服务端实例...", servers.size());
            for (Map.Entry<String, TcpServer> entry : servers.entrySet()) {
                TcpServer server = entry.getValue();
                if (!server.isRunning()) {
                    server.start();
                }
            }
            this.running = true;
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    @Override
    public void stop() {
        lifecycleLock.lock();
        try {
            if (!running) {
                return;
            }
            log.info("正在停止 TCP 服务端注册表，关闭全部 {} 个服务端实例...", servers.size());
            for (Map.Entry<String, TcpServer> entry : servers.entrySet()) {
                TcpServer server = entry.getValue();
                if (server.isRunning()) {
                    server.stop();
                }
            }
            this.running = false;
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
