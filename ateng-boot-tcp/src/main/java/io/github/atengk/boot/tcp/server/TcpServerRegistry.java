package io.github.atengk.boot.tcp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private volatile boolean running = false;

    /**
     * 注册 TCP 服务端实例
     *
     * @param name   服务端唯一名称
     * @param server 服务端实例
     */
    public void register(String name, TcpServer server) {
        Assert.hasText(name, "服务端名称不能为空");
        Assert.notNull(server, "服务端实例不能为空");
        servers.put(name, server);
        log.info("已注册 TCP 服务端实例: name={}, host={}, port={}",
                name, server.getProperties().getHost(), server.getProperties().getPort());
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
    public synchronized void start() {
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
    }

    @Override
    public synchronized void stop() {
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
