package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TCP 云原生可观测性与 Actuator 管控端点自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = {TcpServerAutoConfiguration.class, TcpClientAutoConfiguration.class})
public class TcpObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public TcpMetricsCollector tcpMetricsCollector(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                                       ObjectProvider<TcpSessionManager> sessionManagerProvider) {
            return new TcpMetricsCollector(meterRegistryProvider.getIfAvailable(), sessionManagerProvider.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public TcpActuatorEndpoint tcpActuatorEndpoint(ObjectProvider<TcpSessionManager> sessionManagerProvider,
                                                       ObjectProvider<TcpServerRegistry> serverRegistryProvider,
                                                       ObjectProvider<TcpClientRegistry> clientRegistryProvider,
                                                       ObjectProvider<TcpMetricsCollector> metricsCollectorProvider) {
            return new TcpActuatorEndpoint(
                    sessionManagerProvider.getIfAvailable(),
                    serverRegistryProvider.getIfAvailable(),
                    clientRegistryProvider.getIfAvailable(),
                    metricsCollectorProvider.getIfAvailable()
            );
        }
    }
}
