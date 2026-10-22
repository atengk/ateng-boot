package io.github.atengk.boot.ftp.registry;

import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.ObjectPool;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FTP 多服务节点受管注册表
 * <p>
 * 集中纳管系统内所有具名 FTP/FTPS 节点实例的连接池与操作门面，
 * 支持动态注册、按名称寻址、默认主节点回退与确定性容器生命周期资源销毁。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class FtpClientRegistry implements AutoCloseable {

    /**
     * 默认主节点标识符
     */
    public static final String DEFAULT_SERVER = "default";

    private final Map<String, FtpTemplate> templates = new ConcurrentHashMap<>();
    private final Map<String, ObjectPool<FTPClient>> pools = new ConcurrentHashMap<>();

    /**
     * 注册具名 FTP 服务节点
     *
     * @param name     节点名称
     * @param template 节点专职操作门面
     * @param pool     节点底层连接池
     */
    public void register(String name, FtpTemplate template, ObjectPool<FTPClient> pool) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(template, "FtpTemplate must not be null");
        Assert.notNull(pool, "ObjectPool must not be null");
        templates.put(name, template);
        pools.put(name, pool);
        log.info("ateng-boot-ftp: 成功注册 FTP 服务节点 [name={}]", name);
    }

    /**
     * 根据节点名称获取对应的操作门面（若为空或空白则降级返回 default 主节点）
     *
     * @param name 节点名称
     * @return 对应的 FtpTemplate 操作门面
     * @throws FtpException 若指定了非空名称但在注册表中不存在则抛出 SERVER_NOT_FOUND
     */
    public FtpTemplate getTemplate(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultTemplate();
        }
        FtpTemplate template = templates.get(name);
        if (template == null) {
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "未找到指定的 FTP 服务节点: " + name);
        }
        return template;
    }

    /**
     * 获取默认主节点的操作门面
     *
     * @return 默认主节点 FtpTemplate
     */
    public FtpTemplate getDefaultTemplate() {
        FtpTemplate defaultTemplate = templates.get(DEFAULT_SERVER);
        if (defaultTemplate == null) {
            // 保底兜底：若无显式 default，取注册表中第一个节点
            if (!templates.isEmpty()) {
                return templates.values().iterator().next();
            }
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "FTP 注册表中未配置任何可用服务节点");
        }
        return defaultTemplate;
    }

    /**
     * 获取指定节点的底层原生连接池
     *
     * @param name 节点名称
     * @return 原生连接池
     */
    public ObjectPool<FTPClient> getPool(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultPool();
        }
        ObjectPool<FTPClient> pool = pools.get(name);
        if (pool == null) {
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "未找到指定的 FTP 服务节点底层连接池: " + name);
        }
        return pool;
    }

    /**
     * 获取默认主节点的底层原生连接池
     *
     * @return 默认原生连接池
     */
    public ObjectPool<FTPClient> getDefaultPool() {
        ObjectPool<FTPClient> defaultPool = pools.get(DEFAULT_SERVER);
        if (defaultPool == null && !pools.isEmpty()) {
            return pools.values().iterator().next();
        }
        return defaultPool;
    }

    /**
     * 判断是否包含指定服务节点
     *
     * @param name 节点名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && templates.containsKey(name);
    }

    /**
     * 获取所有已注册的节点名称只读集合
     *
     * @return 节点名称集合
     */
    public Set<String> getServerNames() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * 容器停机销毁回调：安全释放与关闭所有受管连接池物理连接
     */
    @Override
    public void close() {
        log.info("ateng-boot-ftp: 正在释放并关闭所有已注册的 FTP 服务节点连接池...");
        for (Map.Entry<String, ObjectPool<FTPClient>> entry : pools.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("ateng-boot-ftp: 节点连接池已关闭 [name={}]", entry.getKey());
            } catch (Exception e) {
                log.warn("ateng-boot-ftp: 关闭节点连接池失败 [name={}]: {}", entry.getKey(), e.getMessage());
            }
        }
        templates.clear();
        pools.clear();
    }
}
