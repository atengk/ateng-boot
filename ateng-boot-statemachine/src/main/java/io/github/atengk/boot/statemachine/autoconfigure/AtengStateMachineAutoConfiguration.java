package io.github.atengk.boot.statemachine.autoconfigure;

import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import io.github.atengk.boot.statemachine.interceptor.LoggingStateMachineInterceptor;
import io.github.atengk.boot.statemachine.interceptor.MetricsStateMachineInterceptor;
import io.github.atengk.boot.statemachine.properties.StateMachineProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * 状态机组件 Spring Boot 自动装配类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ateng.boot.statemachine", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StateMachineProperties.class)
public class AtengStateMachineAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.statemachine.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(LoggingStateMachineInterceptor.class)
    public LoggingStateMachineInterceptor loggingStateMachineInterceptor(StateMachineProperties properties) {
        return new LoggingStateMachineInterceptor(properties.getLogging());
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ateng.boot.statemachine.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(MetricsStateMachineInterceptor.class)
    public MetricsStateMachineInterceptor metricsStateMachineInterceptor(MeterRegistry meterRegistry) {
        return new MetricsStateMachineInterceptor(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(StateMachineInitializer.class)
    public StateMachineInitializer stateMachineInitializer(
            ObjectProvider<List<StateMachineInterceptor<?, ?, ?>>> interceptorsProvider) {
        List<StateMachineInterceptor<?, ?, ?>> interceptors = interceptorsProvider.getIfAvailable(Collections::emptyList);
        return new StateMachineInitializer(interceptors);
    }
}
