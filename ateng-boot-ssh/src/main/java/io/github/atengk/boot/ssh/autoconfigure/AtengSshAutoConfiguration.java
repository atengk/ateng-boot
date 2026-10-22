package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;

/**
 * SSH 远程治理与通信基础设施防御性自动装配
 * <p>
 * 严格遵循 Spring Boot 3 自动装配规范，提供底层 Apache MINA SshClient 生命周期纳管、
 * 命名多主机注册表、纯内存服务端指纹校验器、受管会话缓存池与三步确定性停机治理协调器。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(SshClient.class)
@ConditionalOnProperty(prefix = "ateng.boot.ssh", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SshProperties.class)
public class AtengSshAutoConfiguration {

    /**
     * 自动装配云原生纯内存服务端公钥指纹校验器（K8s 只读根文件系统安全适配）
     *
     * @param properties 全局配置属性
     * @return 纯内存校验器
     */
    @Bean
    @ConditionalOnMissingBean
    public ServerKeyVerifier serverKeyVerifier(SshProperties properties) {
        return new InMemoryServerKeyVerifier(properties.getHostKeyPolicy());
    }

    /**
     * 自动装配 SSH 具名多节点注册表
     *
     * @param properties 全局配置属性
     * @return 命名注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SshClientRegistry sshClientRegistry(SshProperties properties) {
        SshClientRegistry registry = new SshClientRegistry();

        // 1. 若配置了默认单节点 host，则注册默认 default 节点
        if (properties.getHost() != null && !properties.getHost().isBlank()) {
            registry.register(SshClientRegistry.DEFAULT_SERVER, SshServerInfo.fromProperties(properties));
        }

        // 2. 注册 YAML 命名多服务器节点字典
        if (properties.getServers() != null && !properties.getServers().isEmpty()) {
            properties.getServers().forEach((name, serverProps) -> {
                registry.register(name, SshServerInfo.fromProperties(serverProps));
            });
        }

        log.info("ateng-boot-ssh: SshClientRegistry 自动装配完成，已纳管节点: {}", registry.getServerNames());
        return registry;
    }

    /**
     * 自动装配底层共享受管 Apache MINA SshClient 实例
     *
     * @param serverKeyVerifier 服务端公钥指纹校验器
     * @return 已经启动的 SshClient 实例
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public SshClient sshClient(ServerKeyVerifier serverKeyVerifier) {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(serverKeyVerifier);
        client.start();
        log.info("ateng-boot-ssh: Apache MINA SshClient 自动装配启动完成");
        return client;
    }

    /**
     * 自动装配受管会话缓存池 SshSessionPool（具备 LRU、TTL 与心跳保活）
     *
     * @param sshClient  底座 SshClient 客户端
     * @param properties 全局配置属性
     * @return 会话池实例
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SshSessionPool sshSessionPool(SshClient sshClient, SshProperties properties) {
        return new SshSessionPool(sshClient, properties.getPool());
    }

    /**
     * 自动装配 SSH 基础设施生命周期受管治理协调器（确定性三步优雅停机）
     *
     * @param sshSessionPool 会话缓存池
     * @param sshClient      底座 SshClient 客户端
     * @return 生命周期协调器
     */
    @Bean
    @ConditionalOnMissingBean
    public SshLifecycleManager sshLifecycleManager(SshSessionPool sshSessionPool, SshClient sshClient) {
        return new SshLifecycleManager(sshSessionPool, sshClient);
    }

    /**
     * 自动装配 SSH 核心操作门面 SshTemplate（同步、流式、脚本与瞬态执行）
     *
     * @param sshSessionPool    会话缓存池
     * @param sshClientRegistry 命名多主机注册表
     * @param properties        全局配置属性
     * @return SshTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SshTemplate sshTemplate(SshSessionPool sshSessionPool,
                                   SshClientRegistry sshClientRegistry,
                                   SshProperties properties,
                                   @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        return new SshTemplate(sshSessionPool, sshClientRegistry, properties, taskExecutorProvider.getIfAvailable());
    }
}


