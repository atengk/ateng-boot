package io.github.atengk.boot.rocketmq.autoconfigure;

import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * RocketMQ 5.x 生产级增强基础设施自动装配核心类
 *
 * <p>统一遵循防御性自动装配铁律，受控于 {@code ateng.boot.rocketmq.enabled} 配置开关。
 * 当未配置 {@code ateng.boot.rocketmq.endpoints} 时，框架执行平滑静默跳过，严禁在未配置关键外部连接信息时盲目初始化导致启动崩溃。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(Producer.class)
@ConditionalOnProperty(prefix = "ateng.boot.rocketmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengRocketmqProperties.class)
public class AtengRocketmqAutoConfiguration {

    private final AtengRocketmqProperties properties;

    public AtengRocketmqAutoConfiguration(AtengRocketmqProperties properties) {
        this.properties = properties;
        if (StringUtils.hasText(properties.getEndpoints())) {
            log.info("[ateng-boot-rocketmq] RocketMQ 5.x 增强基础设施自动装配已激活 (endpoints={}, requestTimeout={})",
                    properties.getEndpoints(),
                    properties.getRequestTimeout());
        } else {
            log.warn("[ateng-boot-rocketmq] 未配置 ateng.boot.rocketmq.endpoints，RocketMQ 增强底座已平滑静默跳过物理客户端初始化");
        }
    }
}
