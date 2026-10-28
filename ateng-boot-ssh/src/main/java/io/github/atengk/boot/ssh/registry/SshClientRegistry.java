package io.github.atengk.boot.ssh.registry;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 多服务节点命名受管注册表
 * <p>
 * 集中纳管系统中所有由 YAML 声明或动态编程式注册的具名 SSH 节点配置描述符，
 * 支持按名称路由寻址、默认主节点优雅回退与只读元数据视图暴露。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshClientRegistry {

    /**
     * 默认主节点标识符
     */
    public static final String DEFAULT_SERVER = "default";

    private final Map<String, SshServerInfo> servers = new ConcurrentHashMap<>();

    /**
     * 注册具名 SSH 服务节点描述符
     *
     * @param name       节点名称
     * @param serverInfo 节点目标连接描述符
     */
    public void register(String name, SshServerInfo serverInfo) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(serverInfo, "SshServerInfo must not be null");
        servers.put(name.trim(), serverInfo);
        log.info("ateng-boot-ssh: 成功注册 SSH 服务节点 [name={}, target={}]", name, serverInfo.toSessionKey());
    }

    /**
     * 从配置属性对象转换并注册具名 SSH 服务节点
     *
     * @param name       节点名称
     * @param properties 配置属性
     */
    public void register(String name, SshProperties properties) {
        Assert.notNull(properties, "SshProperties must not be null");
        register(name, SshServerInfo.fromProperties(properties));
    }

    /**
     * 根据节点名称获取对应的连接描述符
     * <p>
     * 若名称为空或空白，则自动降级返回 {@code default} 主节点。
     * </p>
     *
     * @param name 节点名称
     * @return 对应的 SshServerInfo 描述符
     * @throws SshOperationException 若指定了非空名称但在注册表中不存在
     */
    public SshServerInfo getServer(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultServer();
        }
        SshServerInfo serverInfo = servers.get(name.trim());
        if (serverInfo == null) {
            throw new SshOperationException(SshResultCode.SERVER_NOT_FOUND, "未找到指定的 SSH 服务节点: " + name);
        }
        return serverInfo;
    }

    /**
     * 获取默认主节点的连接描述符
     *
     * @return 默认主节点描述符
     * @throws SshOperationException 若注册表中没有任何可用节点
     */
    public SshServerInfo getDefaultServer() {
        SshServerInfo defaultServer = servers.get(DEFAULT_SERVER);
        if (defaultServer == null) {
            // 保底兜底：若无显式 default，取注册表中第一个节点
            if (!servers.isEmpty()) {
                return servers.values().iterator().next();
            }
            throw new SshOperationException(SshResultCode.SERVER_NOT_FOUND, "SSH 注册表中未配置任何可用服务节点");
        }
        return defaultServer;
    }

    /**
     * 判断是否包含指定服务节点
     *
     * @param name 节点名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && servers.containsKey(name.trim());
    }

    /**
     * 获取所有已注册的节点名称只读集合
     *
     * @return 节点名称集合
     */
    public Set<String> getServerNames() {
        return Collections.unmodifiableSet(servers.keySet());
    }

    /**
     * 获取所有已注册的节点映射只读视图
     *
     * @return 节点映射表
     */
    public Map<String, SshServerInfo> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    /**
     * 清理所有已注册的节点信息
     */
    public void clear() {
        servers.clear();
    }
}
