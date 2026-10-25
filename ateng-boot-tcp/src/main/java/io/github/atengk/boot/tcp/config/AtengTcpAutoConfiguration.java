package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.netty.bootstrap.Bootstrap;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ateng-boot-tcp 核心基础设施自动装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration
@ConditionalOnClass(Bootstrap.class)
@EnableConfigurationProperties(AtengTcpProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.tcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengTcpAutoConfiguration {

}
