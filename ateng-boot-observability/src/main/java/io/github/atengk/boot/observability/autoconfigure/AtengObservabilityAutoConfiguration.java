package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ateng-boot 云原生可观测性底座自动装配核心入口类
 * 提供 Actuator 度量导出、线程池监控、高可用探针与端点安全防护自动装配驱动
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengObservabilityAutoConfiguration {

    public AtengObservabilityAutoConfiguration() {
        log.info("ateng-boot: Initialized Observability Infrastructure Starter");
    }
}
