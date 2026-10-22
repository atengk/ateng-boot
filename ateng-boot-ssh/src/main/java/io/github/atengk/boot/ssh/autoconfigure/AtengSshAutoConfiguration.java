package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.properties.SshProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * SSH 远程治理与通信基础设施防御性自动装配
 * <p>
 * 严格遵循 Spring Boot 3 自动装配规范，提供底层 Apache MINA SshClient 生命周期纳管与强类型属性装配。
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
     * 自动装配底层共享受管 Apache MINA SshClient 实例
     *
     * @return 已经启动的 SshClient 实例
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public SshClient sshClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        log.info("ateng-boot-ssh: Apache MINA SshClient 自动装配启动完成");
        return client;
    }
}
