package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.metrics.AtengThreadPoolMeterBinder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * ateng-boot 度量体系自动装配类
 * 负责装配 Prometheus 指标导出与受管线程池度量绑定器
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration(after = AtengObservabilityAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, MeterBinder.class})
@ConditionalOnProperty(prefix = "ateng.boot.observability.metrics.thread-pool", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengMetricsAutoConfiguration {

    /**
     * 装配受管线程池度量绑定器
     *
     * @param applicationContext Spring 上下文
     * @return AtengThreadPoolMeterBinder 实例
     */
    @Bean
    @ConditionalOnMissingBean(AtengThreadPoolMeterBinder.class)
    public AtengThreadPoolMeterBinder atengThreadPoolMeterBinder(ApplicationContext applicationContext) {
        AtengThreadPoolMeterBinder binder = new AtengThreadPoolMeterBinder();
        binder.setApplicationContext(applicationContext);
        log.info("ateng-boot: Initialized AtengThreadPoolMeterBinder for thread pool metrics");
        return binder;
    }
}
