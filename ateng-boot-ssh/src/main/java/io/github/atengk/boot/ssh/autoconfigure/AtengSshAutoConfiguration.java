package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.interceptor.HighRiskCommandInterceptor;
import io.github.atengk.boot.ssh.interceptor.SshCommandInterceptor;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SftpTemplate;
import io.github.atengk.boot.ssh.template.SshTemplate;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
        registry.registerProperties(properties);

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
        client.setForwardingFilter(SshTunnelHandle.REMOTE_FORWARDED_ONLY_FILTER);
        client.start();
        log.info("ateng-boot-ssh: Apache MINA SshClient 自动装配启动完成");
        return client;
    }

    /**
     * 自动装配受管会话缓存池 SshSessionPool（具备 LRU、TTL 与心跳保活）
     *
     * @param sshClient              底座 SshClient 客户端
     * @param properties             全局配置属性
     * @param taskSchedulerProvider  可选共享调度器提供者
     * @param clientRegistryProvider 可选命名主机注册表提供者（用于绑定注销联动驱逐）
     * @return 会话池实例
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SshSessionPool sshSessionPool(SshClient sshClient,
                                         SshProperties properties,
                                         ObjectProvider<ThreadPoolTaskScheduler> taskSchedulerProvider,
                                         ObjectProvider<SshClientRegistry> clientRegistryProvider) {
        SshSessionPool pool = new SshSessionPool(
                sshClient,
                properties.getPool(),
                taskSchedulerProvider.getIfAvailable() != null ? taskSchedulerProvider.getIfAvailable().getScheduledExecutor() : null
        );
        clientRegistryProvider.ifAvailable(registry -> registry.addEvictionListener(pool::evict));
        return pool;
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
     * 自动装配内置高危毁灭性命令安全防火墙拦截器（默认开启，可通过 dangerous-command-guard-enabled 关闭）
     *
     * @param properties 全局配置属性
     * @return 高危命令防火墙拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.ssh", name = "dangerous-command-guard-enabled", havingValue = "true", matchIfMissing = true)
    public HighRiskCommandInterceptor highRiskCommandInterceptor(SshProperties properties) {
        return new HighRiskCommandInterceptor(properties);
    }

    /**
     * 自动装配 SSH 核心操作门面 SshTemplate（同步、流式、脚本与瞬态执行）
     *
     * @param sshSessionPool         会话缓存池
     * @param sshClientRegistry      命名多主机注册表
     * @param properties             全局配置属性
     * @param taskExecutorProvider   可选受管异步执行器提供者
     * @param lifecycleManagerProvider 可选生命周期协调器提供者
     * @param interceptorsProvider   可选命令执行拦截器列表提供者（按 @Order 顺序注入）
     * @return SshTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SshTemplate sshTemplate(SshSessionPool sshSessionPool,
                                   SshClientRegistry sshClientRegistry,
                                   SshProperties properties,
                                   @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                                   ObjectProvider<SshLifecycleManager> lifecycleManagerProvider,
                                   ObjectProvider<SshCommandInterceptor> interceptorsProvider) {
        return new SshTemplate(sshSessionPool, sshClientRegistry, properties,
                taskExecutorProvider.getIfAvailable(),
                lifecycleManagerProvider.getIfAvailable(),
                interceptorsProvider.orderedStream().toList());
    }

    /**
     * 自动装配 SFTP 工业级文件传输门面 SftpTemplate
     *
     * @param sshSessionPool    会话缓存池
     * @param sshClientRegistry 命名多主机注册表
     * @param properties        全局配置属性
     * @return SftpTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(SftpClientFactory.class)
    public SftpTemplate sftpTemplate(SshSessionPool sshSessionPool,
                                     SshClientRegistry sshClientRegistry,
                                     SshProperties properties) {
        return new SftpTemplate(sshSessionPool, sshClientRegistry, properties);
    }
}


