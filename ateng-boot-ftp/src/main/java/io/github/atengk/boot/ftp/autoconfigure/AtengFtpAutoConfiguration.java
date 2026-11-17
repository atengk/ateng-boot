package io.github.atengk.boot.ftp.autoconfigure;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.pool.FtpClientPooledObjectFactory;
import io.github.atengk.boot.ftp.properties.FtpPoolProperties;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FTP / FTPS 远程文件传输与连接池治理防御性自动装配
 * <p>
 * 严格遵循 Spring Boot 3 自动装配四道铁律与零臃肿底座原则，
 * 支持主服务器配置开箱即用、servers 命名多节点级联声明、多节点注册表注入、
 * 线程动态切源与 {@link FtpClientCustomizer} 扩展点按序回调。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({FTPClient.class, GenericObjectPool.class})
@ConditionalOnProperty(prefix = "ateng.boot.ftp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FtpProperties.class)
public class AtengFtpAutoConfiguration {

    /**
     * 自动装配 FTP 多节点受管注册表
     *
     * @param properties          全局与多节点配置属性
     * @param customizersProvider 客户端定制器 SPI 提供者
     * @return 注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    public FtpClientRegistry ftpClientRegistry(FtpProperties properties,
                                               ObjectProvider<FtpClientCustomizer> customizersProvider) {
        List<FtpClientCustomizer> customizers = customizersProvider != null
                ? customizersProvider.orderedStream().toList()
                : Collections.emptyList();

        FtpClientRegistry registry = new FtpClientRegistry();

        // 1. 装配并注册默认主节点 (default)
        ObjectPool<FTPClient> defaultPool = createObjectPool(FtpClientRegistry.DEFAULT_SERVER, properties, customizers);
        FtpTemplate defaultTemplate = new FtpTemplate(defaultPool, properties, registry, FtpClientRegistry.DEFAULT_SERVER);
        registry.register(FtpClientRegistry.DEFAULT_SERVER, defaultTemplate, defaultPool);

        // 2. 装配并注册命名多节点 (servers)
        Map<String, FtpProperties> servers = properties.getServers();
        if (servers != null && !servers.isEmpty()) {
            servers.forEach((name, nodeProps) -> {
                if (nodeProps != null && !FtpClientRegistry.DEFAULT_SERVER.equalsIgnoreCase(name)) {
                    nodeProps.mergeFrom(properties);
                    ObjectPool<FTPClient> nodePool = createObjectPool(name, nodeProps, customizers);
                    FtpTemplate nodeTemplate = new FtpTemplate(nodePool, nodeProps, registry, name);
                    registry.register(name, nodeTemplate, nodePool);
                }
            });
        }

        log.info("ateng-boot-ftp: 自动装配已完成，成功注册服务节点: {}", registry.getServerNames());
        return registry;
    }

    /**
     * 自动装配对外核心操作门面（默认回退 default 主节点并内聚动态切源寻址能力）
     *
     * @param registry 多节点注册表
     * @return 核心操作门面
     */
    @Bean
    @ConditionalOnMissingBean
    public FtpTemplate ftpTemplate(FtpClientRegistry registry) {
        return registry.getDefaultTemplate();
    }

    /**
     * 基于节点配置构建受管连接池
     *
     * @param serverName  服务节点名称
     * @param props       节点配置
     * @param customizers 客户端定制器扩展链
     * @return Commons Pool2 受管对象池
     */
    private ObjectPool<FTPClient> createObjectPool(String serverName,
                                                   FtpProperties props,
                                                   List<FtpClientCustomizer> customizers) {
        FtpClientPooledObjectFactory factory = new FtpClientPooledObjectFactory(serverName, props, customizers);
        GenericObjectPoolConfig<FTPClient> config = new GenericObjectPoolConfig<>();
        FtpPoolProperties poolProps = props.getPool() != null ? props.getPool() : new FtpPoolProperties();

        config.setMaxTotal(poolProps.getMaxTotal());
        config.setMaxIdle(poolProps.getMaxIdle());
        config.setMinIdle(poolProps.getMinIdle());
        config.setMaxWait(poolProps.getMaxWait());
        config.setTestOnBorrow(poolProps.isTestOnBorrow());
        config.setTestOnReturn(poolProps.isTestOnReturn());
        config.setTestWhileIdle(poolProps.isTestWhileIdle());
        config.setTimeBetweenEvictionRuns(poolProps.getTimeBetweenEvictionRuns());
        config.setMinEvictableIdleDuration(poolProps.getMinEvictableIdleTime());

        return new GenericObjectPool<>(factory, config);
    }
}
