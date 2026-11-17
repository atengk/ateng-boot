package io.github.atengk.boot.ftp.autoconfigure;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.impl.GenericObjectPool;
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

        FtpClientRegistry registry = new FtpClientRegistry(properties, customizers);

        // 1. 装配并注册默认主节点 (default)
        registry.register(FtpClientRegistry.DEFAULT_SERVER, properties);

        // 2. 装配并注册命名多节点 (servers)
        Map<String, FtpProperties> servers = properties.getServers();
        if (servers != null && !servers.isEmpty()) {
            servers.forEach((name, nodeProps) -> {
                if (nodeProps != null && !FtpClientRegistry.DEFAULT_SERVER.equalsIgnoreCase(name)) {
                    registry.register(name, nodeProps);
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
}
