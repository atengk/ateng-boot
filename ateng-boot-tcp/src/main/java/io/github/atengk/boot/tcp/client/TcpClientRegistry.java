package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 命名多客户端注册表
 * <p>
 * 集中管理应用内建立的多个外部独立 TCP 长连接实例，支持按名称检索客户端实体及强类型门面 {@link TcpClientTemplate}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpClientRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpClientRegistry.class);

    /**
     * 默认主客户端名称标识
     */
    public static final String DEFAULT_CLIENT_NAME = "default";

    private final Map<String, TcpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, TcpClientTemplate> templates = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean running = false;

    /**
     * 注册 TCP 客户端实例
     *
     * @param name   客户端名称
     * @param client 客户端实例
     */
    public void register(String name, TcpClient client) {
        register(name, client, null);
    }

    /**
     * 注册 TCP 客户端实例及其对应操作门面（若存在同名不同引用的运行中旧实例，将先受控平滑停止旧实例后再纳管新实例）
     *
     * @param name     客户端名称
     * @param client   客户端实例
     * @param template 客户端门面
     */
    public void register(String name, TcpClient client, TcpClientTemplate template) {
        Assert.hasText(name, "客户端名称不能为空");
        Assert.notNull(client, "客户端实例不能为空");
        lifecycleLock.lock();
        try {
            TcpClient existing = clients.get(name);
            if (existing != null && existing != client && existing.isRunning()) {
                log.info("检测到同名 TCP 客户端覆盖注册，正在平滑停止旧实例: name={}", name);
                existing.stop();
            }
            clients.put(name, client);
            if (template != null) {
                templates.put(name, template);
            } else {
                templates.remove(name);
            }
            log.info("已注册 TCP 客户端实例: name={}, host={}, port={}",
                    name, client.getProperties().getHost(), client.getProperties().getPort());
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    /**
     * 注销并移除指定名称的 TCP 客户端及其关联门面（若处于运行态将自动平滑停止）
     *
     * @param name 客户端名称
     * @return 被移除的客户端实例，不存在时返回 null
     */
    public TcpClient unregister(String name) {
        if (name == null) {
            return null;
        }
        lifecycleLock.lock();
        try {
            templates.remove(name);
            TcpClient removed = clients.remove(name);
            if (removed != null && removed.isRunning()) {
                log.info("注销 TCP 客户端实例，正在平滑停止: name={}", name);
                removed.stop();
            }
            return removed;
        } finally {
            if (lifecycleLock.isHeldByCurrentThread()) {
                lifecycleLock.unlock();
            }
        }
    }

    /**
     * 判断是否已注册指定名称的 TCP 客户端实例
     *
     * @param name 客户端名称
     * @return true 表示存在
     */
    public boolean contains(String name) {
        return name != null && clients.containsKey(name);
    }

    /**
     * 根据名称获取 TCP 客户端实例
     *
     * @param name 客户端名称
     * @return 客户端实例，不存在时返回 null
     */
    public TcpClient getClient(String name) {
        if (name == null) {
            return null;
        }
        return clients.get(name);
    }

    /**
     * 根据名称获取 TCP 客户端实例，不存在时抛出异常
     *
     * @param name 客户端名称
     * @return 客户端实例
     */
    public TcpClient getRequiredClient(String name) {
        TcpClient client = getClient(name);
        if (client == null) {
            throw new IllegalArgumentException("未找到名称为 [" + name + "] 的 TCP 客户端实例，已注册列表: " + clients.keySet());
        }
        return client;
    }

    /**
     * 获取默认主客户端实例
     *
     * @return 默认客户端实例，不存在时返回 null
     */
    public TcpClient getDefaultClient() {
        return getClient(DEFAULT_CLIENT_NAME);
    }

    /**
     * 根据名称获取对应的 TCP 客户端操作门面
     *
     * @param name 客户端名称
     * @return 客户端门面，不存在时返回 null
     */
    public TcpClientTemplate getTemplate(String name) {
        if (name == null) {
            return null;
        }
        return templates.get(name);
    }

    /**
     * 根据名称获取对应的 TCP 客户端操作门面，不存在时抛出异常
     *
     * @param name 客户端名称
     * @return 客户端门面
     */
    public TcpClientTemplate getRequiredTemplate(String name) {
        TcpClientTemplate template = getTemplate(name);
        if (template == null) {
            throw new IllegalArgumentException("未找到名称为 [" + name + "] 的 TCP 客户端操作门面，已注册列表: " + templates.keySet());
        }
        return template;
    }

    /**
     * 获取默认主客户端操作门面
     *
     * @return 默认门面，不存在时返回 null
     */
    public TcpClientTemplate getDefaultTemplate() {
        return getTemplate(DEFAULT_CLIENT_NAME);
    }

    /**
     * 获取全部已注册客户端只读映射表
     *
     * @return 不可变映射表
     */
    public Map<String, TcpClient> getAllClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * 获取全部已注册客户端门面只读映射表
     *
     * @return 不可变映射表
     */
    public Map<String, TcpClientTemplate> getAllTemplates() {
        return Collections.unmodifiableMap(templates);
    }

    @Override
    public void start() {
        lifecycleLock.lock();
        try {
            if (running) {
                return;
            }
            log.info("正在启动 TCP 客户端注册表，共纳管 {} 个客户端实例...", clients.size());
            for (Map.Entry<String, TcpClient> entry : clients.entrySet()) {
                TcpClient client = entry.getValue();
                if (!client.isRunning()) {
                    client.start();
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
            log.info("正在停止 TCP 客户端注册表，关闭全部 {} 个客户端实例...", clients.size());
            for (Map.Entry<String, TcpClient> entry : clients.entrySet()) {
                TcpClient client = entry.getValue();
                if (client.isRunning()) {
                    client.stop();
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
