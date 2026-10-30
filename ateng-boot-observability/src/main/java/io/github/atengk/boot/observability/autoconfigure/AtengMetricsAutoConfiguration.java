package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.aspect.ObservedMetricAspect;
import io.github.atengk.boot.observability.metrics.AtengThreadPoolMeterBinder;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * ateng-boot 度量体系自动装配类
 * <p>
 * 负责装配 Prometheus 指标导出、业务度量操作门面 {@link MetricsTemplate}、
 * 声明式方法性能度量切面 {@link ObservedMetricAspect} 与受管线程池动态度量绑定器。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration(after = AtengObservabilityAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "ateng.boot.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengMetricsAutoConfiguration {

    /**
     * 装配业务度量编程式操作门面
     *
     * @param meterRegistry 度量注册表
     * @return MetricsTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(MetricsTemplate.class)
    public MetricsTemplate metricsTemplate(MeterRegistry meterRegistry) {
        log.info("ateng-boot: Initialized MetricsTemplate for programmatic metric instrumentation");
        return new MetricsTemplate(meterRegistry);
    }

    /**
     * 装配声明式方法度量切面
     *
     * @param metricsTemplate 度量操作门面
     * @return ObservedMetricAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean(ObservedMetricAspect.class)
    @ConditionalOnClass(ProceedingJoinPoint.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.metrics.observed", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ObservedMetricAspect observedMetricAspect(MetricsTemplate metricsTemplate) {
        log.info("ateng-boot: Initialized ObservedMetricAspect for @ObservedMetric annotations");
        return new ObservedMetricAspect(metricsTemplate);
    }

    /**
     * 装配受管线程池度量绑定器
     *
     * @param applicationContext Spring 上下文
     * @return AtengThreadPoolMeterBinder 实例
     */
    @Bean
    @ConditionalOnMissingBean(AtengThreadPoolMeterBinder.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.metrics.thread-pool", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengThreadPoolMeterBinder atengThreadPoolMeterBinder(ApplicationContext applicationContext) {
        AtengThreadPoolMeterBinder binder = new AtengThreadPoolMeterBinder();
        binder.setApplicationContext(applicationContext);
        log.info("ateng-boot: Initialized AtengThreadPoolMeterBinder for thread pool metrics");
        return binder;
    }
}
